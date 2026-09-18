package com.llama.hub.service;

import lombok.extern.slf4j.Slf4j;
import com.llama.hub.model.ApiKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.node.ObjectNode;

@Service
@Slf4j
public class ProxyService {


    // authorization 必须剥离：网关 API Key 只用于网关鉴权，不得透传给上游模型服务器
    private static final Set<String> HOP_HEADERS = new HashSet<>(Arrays.asList(
            "host", "content-length", "connection", "accept-encoding",
            "transfer-encoding", "upgrade", "keep-alive", "te", "trailer", "proxy-connection",
            "authorization"));

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final CallRecorder callRecorder;

    @Value("${gateway.upstream:http://127.0.0.1:18082}")
    private String upstream;

    @Value("${gateway.log.body-enabled:false}")
    private boolean bodyEnabled;

    public ProxyService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper,
                        CallRecorder callRecorder) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
        this.callRecorder = callRecorder;
    }

    public CompletableFuture<Void> proxy(HttpServletRequest req, HttpServletResponse resp) {
        ApiKey apiKey = (ApiKey) req.getAttribute(com.llama.hub.filter.ApiKeyAuthFilter.ATTR_API_KEY);
        String requestId = UUID.randomUUID().toString().replace("-", "");
        String endpoint = req.getRequestURI();
        LocalDateTime startedAt = LocalDateTime.now();

        byte[] requestBody = readAllSafely(req);
        if (requestBody != null && requestBody.length > 0) {
            requestBody = injectStreamOptions(requestBody, endpoint);
        }
        String requestBodyStr = bodyEnabled ? truncate(new String(requestBody, StandardCharsets.UTF_8), 2000) : null;
        String model = extractModel(requestBody);

        HttpMethod method;
        try {
            method = HttpMethod.valueOf(req.getMethod());
        } catch (IllegalArgumentException e) {
            method = HttpMethod.GET;
        }
        String target = upstream + endpoint;

        AtomicReference<Integer> statusRef = new AtomicReference<>(502);
        AtomicReference<String> errorRef = new AtomicReference<>("upstream unreachable");
        AtomicReference<String> latestChunk = new AtomicReference<>("");
        AtomicReference<String> responseBodyRef = new AtomicReference<>(null);
        AtomicReference<Integer> anthropicInputTokensRef = new AtomicReference<>(null);
        AtomicReference<Integer> anthropicCachedTokensRef = new AtomicReference<>(null);

        WebClient.RequestBodySpec bodySpec = webClient.method(method).uri(target)
                .headers(h -> h.putAll(copyRequestHeaders(req)));

        Mono<Void> responseMono;
        if (requestBody != null && requestBody.length > 0) {
            responseMono = bodySpec.bodyValue(requestBody)
                    .exchangeToMono(cr -> handleResponse(cr, resp, statusRef, errorRef, latestChunk, responseBodyRef, anthropicInputTokensRef, anthropicCachedTokensRef));
        } else {
            responseMono = bodySpec
                    .exchangeToMono(cr -> handleResponse(cr, resp, statusRef, errorRef, latestChunk, responseBodyRef, anthropicInputTokensRef, anthropicCachedTokensRef));
        }

        return responseMono
                .onErrorResume(e -> Mono.fromRunnable(() -> {
                    errorRef.compareAndSet("upstream unreachable", describeError(e));
                    if (!resp.isCommitted()) {
                        writeJsonToResponse(resp, 502, errorRef.get());
                    }
                    statusRef.set(502);
                }))
                .doFinally(signal -> callRecorder.record(apiKey, requestId, endpoint, model, requestBodyStr,
                        startedAt, statusRef.get(), errorRef.get(), latestChunk.get(), responseBodyRef.get()))
                .then()
                .toFuture();
    }

    private Mono<Void> handleResponse(ClientResponse cr, HttpServletResponse resp,
                                      AtomicReference<Integer> statusRef, AtomicReference<String> errorRef,
                                      AtomicReference<String> latestChunk, AtomicReference<String> responseBodyRef,
                                      AtomicReference<Integer> anthropicInputTokensRef,
                                      AtomicReference<Integer> anthropicCachedTokensRef) {
        int upstreamStatus = cr.statusCode().value();
        String contentType = cr.headers().contentType().map(Object::toString).orElse("");
        boolean streaming = contentType.contains("ndjson") || contentType.contains("event-stream");

        if (streaming) {
            resp.setStatus(200);
            copyResponseHeaders(cr, resp);
            statusRef.set(200);
            errorRef.set(null);
            try {
                OutputStream out = resp.getOutputStream();
                ByteArrayOutputStream pending = new ByteArrayOutputStream();
                return cr.bodyToFlux(byte[].class)
                        .doOnNext(chunk -> {
                            try {
                                pending.write(chunk, 0, chunk.length);
                                byte[] data = pending.toByteArray();
                                pending.reset();
                                int start = 0;
                                for (int i = 0; i < data.length; i++) {
                                    if (data[i] == '\n') {
                                        String line = new String(data, start, i - start, StandardCharsets.UTF_8);
                                        start = i + 1;
                                        if (line.endsWith("\r")) {
                                            line = line.substring(0, line.length() - 1);
                                        }
                                        String processed = processStreamLine(line, anthropicInputTokensRef, anthropicCachedTokensRef);
                                        if (processed.contains("\"usage\"") || processed.contains("\"timings\"")) {
                                            latestChunk.set(processed);
                                        }
                                        out.write((processed + "\n").getBytes(StandardCharsets.UTF_8));
                                    }
                                }
                                if (start < data.length) {
                                    pending.write(data, start, data.length - start);
                                }
                                out.flush();
                            } catch (IOException e) {
                                errorRef.set("client disconnected: " + e.getMessage());
                                throw new RuntimeException(e);
                            }
                        })
                        .doOnComplete(() -> {
                            try {
                                byte[] rest = pending.toByteArray();
                                if (rest.length > 0) {
                                    String line = new String(rest, StandardCharsets.UTF_8);
                                    out.write(processStreamLine(line, anthropicInputTokensRef, anthropicCachedTokensRef).getBytes(StandardCharsets.UTF_8));
                                    out.flush();
                                }
                            } catch (IOException e) {
                                errorRef.set("client disconnected: " + e.getMessage());
                            }
                        })
                        .then()
                        .onErrorResume(e -> Mono.fromRunnable(() -> {
                            if (errorRef.get() == null) {
                                errorRef.set(describeError(e));
                            }
                        }));
            } catch (IOException e) {
                errorRef.set("failed to open response stream");
                return Mono.empty();
            }
        } else {
            return cr.bodyToMono(byte[].class)
                    .map(body -> {
                        String text = body == null ? "" : new String(body, StandardCharsets.UTF_8);
                        resp.setStatus(upstreamStatus);
                        copyResponseHeaders(cr, resp);
                        try {
                            if (body != null && body.length > 0) {
                                resp.setContentLength(body.length);
                                resp.getOutputStream().write(body);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        latestChunk.set(text);
                        responseBodyRef.set(bodyEnabled ? truncate(text, 2000) : null);
                        statusRef.set(upstreamStatus);
                        errorRef.set(upstreamStatus >= 400 ? truncate(text, 500) : null);
                        return text;
                    })
                    .then()
                    .onErrorResume(e -> Mono.fromRunnable(() -> {
                        errorRef.set(describeError(e));
                        if (!resp.isCommitted()) {
                            writeJsonToResponse(resp, 502, errorRef.get());
                        }
                        statusRef.set(502);
                    }));
        }
    }

    // ---------- helpers ----------

    private HttpHeaders copyRequestHeaders(HttpServletRequest req) {
        HttpHeaders headers = new HttpHeaders();
        Enumeration<String> names = req.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            if (HOP_HEADERS.contains(name.toLowerCase())) {
                continue;
            }
            Enumeration<String> values = req.getHeaders(name);
            while (values.hasMoreElements()) {
                headers.add(name, values.nextElement());
            }
        }
        return headers;
    }

    private void copyResponseHeaders(ClientResponse cr, HttpServletResponse resp) {
        cr.headers().asHttpHeaders().forEach((name, values) -> {
            if (HOP_HEADERS.contains(name.toLowerCase())) {
                return;
            }
            for (String v : values) {
                resp.addHeader(name, v);
            }
        });
    }

    private void writeJsonToResponse(HttpServletResponse resp, int status, String message) {
        try {
            if (resp.isCommitted()) {
                return;
            }
            resp.setStatus(status);
            resp.setContentType("application/json;charset=UTF-8");
            String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r");
            resp.getWriter().write("{\"error\":{\"message\":\"" + escaped + "\",\"type\":\"upstream_error\"}}");
        } catch (IOException e) {
            log.warn("Failed to write error response", e);
        }
    }

    private byte[] readAllSafely(HttpServletRequest req) {
        try {
            return readAll(req.getInputStream());
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int read;
        while ((read = in.read(buf)) != -1) {
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    private String extractModel(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode model = node.path("model");
            return model.isTextual() ? model.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Integer intValue(JsonNode node) {
        return node.isNumber() ? node.asInt() : null;
    }

    private ObjectNode buildAnthropicUsage(Integer inputTokens, Integer outputTokens, Integer cachedTokens) {
        ObjectNode usage = objectMapper.createObjectNode();
        if (inputTokens != null) {
            usage.put("input_tokens", inputTokens);
        }
        if (outputTokens != null) {
            usage.put("output_tokens", outputTokens);
        }
        if (inputTokens != null && outputTokens != null) {
            usage.put("total_tokens", inputTokens + outputTokens + (cachedTokens != null ? cachedTokens : 0));
        }
        if (cachedTokens != null) {
            usage.put("cache_read_input_tokens", cachedTokens);
        }
        return usage;
    }

    private byte[] injectStreamOptions(byte[] body, String endpoint) {
        if (body == null || body.length == 0) {
            return body;
        }
        // stream_options.include_usage 是 OpenAI 专有字段，Anthropic Messages 端点无需注入
        if (endpoint != null && endpoint.endsWith("/messages")) {
            return body;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (!node.isObject()) {
                return body;
            }
            JsonNode stream = node.path("stream");
            if (!stream.isBoolean() || !stream.asBoolean()) {
                return body;
            }
            JsonNode streamOptions = node.path("stream_options");
            if (!streamOptions.isMissingNode()) {
                return body;
            }
            ObjectNode enriched = (ObjectNode) node.deepCopy();
            ObjectNode options = objectMapper.createObjectNode();
            options.put("include_usage", true);
            enriched.set("stream_options", options);
            return objectMapper.writeValueAsBytes(enriched);
        } catch (Exception e) {
            return body;
        }
    }

    private String processStreamLine(String line, AtomicReference<Integer> anthropicInputTokensRef,
                                     AtomicReference<Integer> anthropicCachedTokensRef) {
        // Anthropic Messages SSE：message_start 携带 input_tokens 与 cache_read_input_tokens，message_delta 携带 output_tokens。
        // 合并为 Anthropic 风格 usage 供 parseUsage 统一采集（否则 Anthropic 流式无法统计用量）。
        if (line.startsWith("data:")) {
            String payload = line.substring(5).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                return line;
            }
            try {
                JsonNode root = objectMapper.readTree(payload);
                String type = root.path("type").asText(null);
                if ("message_start".equals(type)) {
                    JsonNode usage = root.path("message").path("usage");
                    Integer inputTokens = intValue(usage.path("input_tokens"));
                    Integer cachedTokens = intValue(usage.path("cache_read_input_tokens"));
                    if (inputTokens != null) {
                        anthropicInputTokensRef.set(inputTokens);
                    }
                    if (cachedTokens != null) {
                        anthropicCachedTokensRef.set(cachedTokens);
                    }
                    ObjectNode enriched = objectMapper.createObjectNode();
                    enriched.set("usage", buildAnthropicUsage(inputTokens, null, cachedTokens));
                    return "data: " + enriched;
                }
                if ("message_delta".equals(type)) {
                    Integer outputTokens = intValue(root.path("usage").path("output_tokens"));
                    Integer inputTokens = anthropicInputTokensRef.get();
                    Integer cachedTokens = anthropicCachedTokensRef.get();
                    ObjectNode enriched = objectMapper.createObjectNode();
                    enriched.set("usage", buildAnthropicUsage(inputTokens, outputTokens, cachedTokens));
                    return "data: " + enriched;
                }
            } catch (Exception e) {
                log.warn("Failed to enrich Anthropic stream chunk with usage", e);
            }
        }
        if (line.startsWith("data: ") && line.contains("\"timings\"") && !line.contains("\"usage\"")) {
            try {
                JsonNode root = objectMapper.readTree(line.substring(6));
                JsonNode timings = root.path("timings");
                if (!timings.isMissingNode()) {
                    ObjectNode enriched = (ObjectNode) root.deepCopy();
                    ObjectNode usage = objectMapper.createObjectNode();
                    Integer cached = intValue(timings.path("cache_n"));
                    Integer promptCalc = intValue(timings.path("prompt_n"));
                    Integer predicted = intValue(timings.path("predicted_n"));
                    Integer promptTokens = (cached != null && promptCalc != null)
                            ? cached + promptCalc : (promptCalc != null ? promptCalc : cached);
                    if (promptTokens != null) {
                        usage.put("prompt_tokens", promptTokens);
                    }
                    if (predicted != null) {
                        usage.put("completion_tokens", predicted);
                    }
                    if (promptTokens != null && predicted != null) {
                        usage.put("total_tokens", promptTokens + predicted);
                    }
                    if (cached != null) {
                        ObjectNode details = objectMapper.createObjectNode();
                        details.put("cached_tokens", cached);
                        usage.set("prompt_tokens_details", details);
                    }
                    enriched.set("usage", usage);
                    return "data: " + enriched;
                }
            } catch (Exception e) {
                log.warn("Failed to enrich stream chunk with usage", e);
            }
        }
        return line;
    }

    private String describeError(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) {
            msg = e.getClass().getSimpleName();
        }
        if (msg.contains("connection refused") || msg.contains("Connection refused") || msg.contains("connect")) {
            return "upstream unreachable";
        }
        return "upstream error: " + msg;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

}
