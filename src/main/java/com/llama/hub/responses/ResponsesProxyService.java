package com.llama.hub.responses;

import com.llama.hub.filter.ApiKeyAuthFilter;
import com.llama.hub.model.ApiKey;
import com.llama.hub.service.CallRecorder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * /v1/responses 数据面：接收 Responses API 请求，经 ResponsesAdapter 转为
 * 上游 /v1/chat/completions 调用，响应（含 SSE 事件流）再转回 Responses 格式。
 * 鉴权/限额由 ApiKeyAuthFilter（/v1/*）完成，用量与调用日志走 CallRecorder（endpoint 记 /v1/responses）。
 */
@Service
@Slf4j
public class ResponsesProxyService {

    private static final Set<String> HOP_HEADERS = new HashSet<>(Arrays.asList(
            "host", "content-length", "connection", "accept-encoding",
            "transfer-encoding", "upgrade", "keep-alive", "te", "trailer", "proxy-connection",
            "authorization"));

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final CallRecorder callRecorder;
    private final ResponsesAdapter adapter;

    @Value("${gateway.upstream:http://127.0.0.1:18082}")
    private String upstream;

    @Value("${gateway.log.body-enabled:false}")
    private boolean bodyEnabled;

    public ResponsesProxyService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper,
                                 CallRecorder callRecorder, ResponsesAdapter adapter) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
        this.callRecorder = callRecorder;
        this.adapter = adapter;
    }

    public CompletableFuture<Void> handle(HttpServletRequest req, HttpServletResponse resp) {
        ApiKey apiKey = (ApiKey) req.getAttribute(ApiKeyAuthFilter.ATTR_API_KEY);
        String requestId = UUID.randomUUID().toString().replace("-", "");
        String endpoint = req.getRequestURI();
        LocalDateTime startedAt = LocalDateTime.now();

        byte[] responsesBody = readAllSafely(req);
        String requestBodyStr = bodyEnabled ? truncate(new String(responsesBody, StandardCharsets.UTF_8), 2000) : null;
        String model = extractModel(responsesBody);

        ResponsesAdapter.PreparedChatRequest prepared;
        try {
            prepared = adapter.toChatRequest(responsesBody);
        } catch (ResponsesAdapter.BadRequestException e) {
            writeError(resp, 400, e.getMessage(), "invalid_request_error");
            callRecorder.record(apiKey, requestId, endpoint, model, requestBodyStr,
                    startedAt, 400, e.getMessage(), null, null);
            return CompletableFuture.completedFuture(null);
        }

        AtomicReference<Integer> statusRef = new AtomicReference<>(502);
        AtomicReference<String> errorRef = new AtomicReference<>("upstream unreachable");
        AtomicReference<String> usageSourceRef = new AtomicReference<>(null);
        AtomicReference<String> responseBodyRef = new AtomicReference<>(null);

        WebClient.RequestBodySpec bodySpec = webClient.post().uri(upstream + "/v1/chat/completions")
                .headers(h -> h.putAll(copyRequestHeaders(req)));

        Mono<Void> responseMono = bodySpec.bodyValue(prepared.body)
                .exchangeToMono(cr -> handleResponse(cr, resp, statusRef, errorRef, usageSourceRef, responseBodyRef,
                        model, prepared.toolsByChatName));

        return responseMono
                .onErrorResume(e -> Mono.fromRunnable(() -> {
                    errorRef.compareAndSet("upstream unreachable", describeError(e));
                    statusRef.set(502);
                    if (!resp.isCommitted()) {
                        writeError(resp, 502, errorRef.get(), "upstream_error");
                    }
                }))
                .doFinally(signal -> callRecorder.record(apiKey, requestId, endpoint, model, requestBodyStr,
                        startedAt, statusRef.get(), errorRef.get(), usageSourceRef.get(), responseBodyRef.get()))
                .then()
                .toFuture();
    }

    private Mono<Void> handleResponse(ClientResponse cr, HttpServletResponse resp,
                                      AtomicReference<Integer> statusRef, AtomicReference<String> errorRef,
                                      AtomicReference<String> usageSourceRef, AtomicReference<String> responseBodyRef,
                                      String model, Map<String, ResponsesAdapter.ToolDescriptor> toolsByChatName) {
        int upstreamStatus = cr.statusCode().value();
        String contentType = cr.headers().contentType().map(Object::toString).orElse("");
        boolean streaming = contentType.contains("ndjson") || contentType.contains("event-stream");

        if (streaming) {
            return handleStreaming(cr, resp, statusRef, errorRef, usageSourceRef, model, toolsByChatName);
        }

        return cr.bodyToMono(byte[].class)
                .defaultIfEmpty(new byte[0])
                .map(body -> {
                    String text = new String(body, StandardCharsets.UTF_8);
                    if (upstreamStatus >= 400) {
                        resp.setStatus(upstreamStatus);
                        copyResponseHeaders(cr, resp);
                        writeBytes(resp, body);
                        statusRef.set(upstreamStatus);
                        errorRef.set(truncate(text, 500));
                        return text;
                    }
                    final String converted;
                    try {
                        JsonNode chatResp = objectMapper.readTree(body);
                        converted = adapter.toResponsesResponse(chatResp, model, toolsByChatName).toString();
                    } catch (Exception e) {
                        String message = "invalid chat response from upstream: " + describeError(e);
                        statusRef.set(502);
                        errorRef.set(message);
                        writeError(resp, 502, message, "upstream_error");
                        return text;
                    }
                    resp.setStatus(200);
                    resp.setContentType("application/json;charset=UTF-8");
                    writeBytes(resp, converted.getBytes(StandardCharsets.UTF_8));
                    statusRef.set(200);
                    errorRef.set(null);
                    usageSourceRef.set(text);
                    responseBodyRef.set(bodyEnabled ? truncate(converted, 2000) : null);
                    return text;
                })
                .then()
                .onErrorResume(e -> Mono.fromRunnable(() -> {
                    errorRef.set(describeError(e));
                    statusRef.set(502);
                    if (!resp.isCommitted()) {
                        writeError(resp, 502, errorRef.get(), "upstream_error");
                    }
                }));
    }

    private Mono<Void> handleStreaming(ClientResponse cr, HttpServletResponse resp,
                                       AtomicReference<Integer> statusRef, AtomicReference<String> errorRef,
                                       AtomicReference<String> usageSourceRef, String model,
                                       Map<String, ResponsesAdapter.ToolDescriptor> toolsByChatName) {
        int upstreamStatus = cr.statusCode().value();
        // 上游流式建连即非 2xx：读完整 body 原样透传
        if (upstreamStatus >= 400) {
            return cr.bodyToMono(byte[].class)
                    .defaultIfEmpty(new byte[0])
                    .map(body -> {
                        resp.setStatus(upstreamStatus);
                        copyResponseHeaders(cr, resp);
                        writeBytes(resp, body);
                        statusRef.set(upstreamStatus);
                        errorRef.set(truncate(new String(body, StandardCharsets.UTF_8), 500));
                        return body;
                    })
                    .then()
                    .onErrorResume(e -> Mono.fromRunnable(() -> {
                        errorRef.set(describeError(e));
                        statusRef.set(502);
                    }));
        }

        ResponsesAdapter.StreamTranslator translator = adapter.new StreamTranslator(model, toolsByChatName);
        resp.setStatus(200);
        resp.setContentType("text/event-stream;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("X-Accel-Buffering", "no");
        statusRef.set(200);
        errorRef.set(null);

        OutputStream out;
        try {
            out = resp.getOutputStream();
            out.write(adapter.utf8(translator.start()));
            out.flush();
        } catch (IOException e) {
            errorRef.set("client disconnected: " + e.getMessage());
            return Mono.empty();
        }

        final OutputStream sink = out;
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        AtomicBoolean upstreamDone = new AtomicBoolean(false);
        return cr.bodyToFlux(byte[].class)
                .doOnNext(chunk -> {
                    try {
                        pending.write(chunk, 0, chunk.length);
                        byte[] data = pending.toByteArray();
                        pending.reset();
                        int start = 0;
                        StringBuilder events = new StringBuilder();
                        for (int i = 0; i < data.length; i++) {
                            if (data[i] == '\n') {
                                String line = new String(data, start, i - start, StandardCharsets.UTF_8);
                                start = i + 1;
                                if (line.endsWith("\r")) {
                                    line = line.substring(0, line.length() - 1);
                                }
                                collectStreamLine(line, translator, events, usageSourceRef, upstreamDone);
                            }
                        }
                        if (start < data.length) {
                            pending.write(data, start, data.length - start);
                        }
                        if (!events.isEmpty()) {
                            sink.write(adapter.utf8(events.toString()));
                            sink.flush();
                        }
                    } catch (IOException e) {
                        errorRef.set("client disconnected: " + e.getMessage());
                        throw new RuntimeException(e);
                    }
                })
                .doOnComplete(() -> {
                    try {
                        byte[] rest = pending.toByteArray();
                        if (rest.length > 0) {
                            StringBuilder events = new StringBuilder();
                            collectStreamLine(new String(rest, StandardCharsets.UTF_8), translator, events,
                                    usageSourceRef, upstreamDone);
                            if (!events.isEmpty()) {
                                sink.write(adapter.utf8(events.toString()));
                            }
                        }
                        if (!translator.isTerminal()) {
                            String message = upstreamDone.get()
                                    ? "upstream stream ended without a terminal response"
                                    : "upstream stream closed before [DONE]";
                            errorRef.set(message);
                            sink.write(adapter.utf8(translator.failed(message)));
                        }
                        sink.flush();
                    } catch (IOException e) {
                        if (errorRef.get() == null) {
                            errorRef.set("client disconnected: " + e.getMessage());
                        }
                    }
                })
                .then()
                .onErrorResume(e -> Mono.fromRunnable(() -> {
                    String msg = describeError(e);
                    if ((errorRef.get() == null || !errorRef.get().startsWith("client disconnected"))
                            && !translator.isTerminal()) {
                        errorRef.set(msg);
                        try {
                            sink.write(adapter.utf8(translator.failed(msg)));
                            sink.flush();
                        } catch (IOException ignored) {
                            // 客户端已断开，无法送达 failed 事件
                        }
                    }
                }));
    }

    /** 处理上游 chat SSE 的一行：data 载荷转 Responses 事件；usage/timings chunk 留作记账来源 */
    void collectStreamLine(String line, ResponsesAdapter.StreamTranslator translator,
                           StringBuilder events, AtomicReference<String> usageSourceRef,
                           AtomicBoolean upstreamDone) {
        if (!line.startsWith("data:")) {
            return;
        }
        String payload = line.substring(5).trim();
        if (payload.isEmpty()) {
            return;
        }
        if ("[DONE]".equals(payload)) {
            upstreamDone.set(true);
            events.append(translator.finish(callRecorder.parseUsage(usageSourceRef.get())));
            return;
        }
        if (payload.contains("\"usage\"") || payload.contains("\"timings\"")) {
            usageSourceRef.set(line);
        }
        events.append(translator.onChunk(payload));
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

    private void writeBytes(HttpServletResponse resp, byte[] body) {
        try {
            if (body != null && body.length > 0) {
                resp.setContentLength(body.length);
                resp.getOutputStream().write(body);
            }
        } catch (IOException e) {
            log.warn("Failed to write response body", e);
        }
    }

    private void writeError(HttpServletResponse resp, int status, String message, String type) {
        try {
            if (resp.isCommitted()) {
                return;
            }
            resp.setStatus(status);
            resp.setContentType("application/json;charset=UTF-8");
            String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r");
            resp.getWriter().write("{\"error\":{\"message\":\"" + escaped + "\",\"type\":\"" + type + "\"}}");
        } catch (IOException e) {
            log.warn("Failed to write error response", e);
        }
    }

    private byte[] readAllSafely(HttpServletRequest req) {
        try {
            InputStream in = req.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
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
