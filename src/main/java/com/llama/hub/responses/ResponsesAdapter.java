package com.llama.hub.responses;

import com.llama.hub.service.CallRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OpenAI Responses API <-> Chat Completions 协议适配（无状态第一期）：
 * - 请求：input/instructions/tools/tool_choice/text.format 等映射为 chat 请求；
 *   previous_response_id / background / 内置工具（web_search 等）显式拒绝
 * - 非流式响应：choices[0].message 映射为 output 数组（function_call + message item）
 * - 流式：chat chunk 流合成 Responses SSE 事件序列（response.created → output_item/content_part/delta → completed）
 */
@Component
@Slf4j
public class ResponsesAdapter {

    private final ObjectMapper objectMapper;

    public ResponsesAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) {
            super(message);
        }
    }

    // ---------- 请求：Responses -> Chat ----------

    public byte[] toChatRequest(byte[] responsesBody) {
        JsonNode req;
        try {
            req = objectMapper.readTree(responsesBody);
        } catch (Exception e) {
            throw new BadRequestException("request body is not valid JSON");
        }
        if (!req.isObject()) {
            throw new BadRequestException("request body must be a JSON object");
        }
        String model = req.path("model").asText(null);
        if (model == null || model.isEmpty()) {
            throw new BadRequestException("field 'model' is required");
        }
        if (req.has("previous_response_id") && !req.path("previous_response_id").isNull()) {
            throw new BadRequestException("'previous_response_id' is not supported: this gateway is stateless, send the full conversation in 'input'");
        }
        if (req.path("background").asBoolean(false)) {
            throw new BadRequestException("'background' mode is not supported");
        }

        ObjectNode chat = objectMapper.createObjectNode();
        chat.put("model", model);
        ArrayNode messages = chat.putArray("messages");

        String instructions = req.path("instructions").asText(null);
        if (instructions != null && !instructions.isEmpty()) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", instructions);
        }

        JsonNode input = req.path("input");
        if (input.isTextual()) {
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", input.asText());
        } else if (input.isArray()) {
            for (JsonNode item : input) {
                appendInputItem(messages, item);
            }
        }

        // 采样参数
        copyNumber(chat, req, "temperature");
        copyNumber(chat, req, "top_p");
        if (req.path("max_output_tokens").isNumber()) {
            chat.set("max_tokens", req.path("max_output_tokens"));
        }
        if (req.path("stop").isNumber() || req.path("stop").isArray() || req.path("stop").isTextual()) {
            chat.set("stop", req.path("stop"));
        }
        if (req.path("parallel_tool_calls").isBoolean()) {
            chat.set("parallel_tool_calls", req.path("parallel_tool_calls"));
        }

        // tools
        JsonNode tools = req.path("tools");
        if (tools.isArray() && !tools.isEmpty()) {
            ArrayNode chatTools = chat.putArray("tools");
            for (JsonNode t : tools) {
                String type = t.path("type").asText("function");
                if (!"function".equals(type)) {
                    throw new BadRequestException("tool type '" + type + "' is not supported: only custom function tools are supported by this gateway");
                }
                JsonNode fn = t.path("function").isObject() ? t.path("function") : t;
                ObjectNode ct = chatTools.addObject();
                ct.put("type", "function");
                ObjectNode cfn = ct.putObject("function");
                cfn.put("name", fn.path("name").asText(""));
                if (fn.has("description")) {
                    cfn.set("description", fn.path("description"));
                }
                if (fn.has("parameters")) {
                    cfn.set("parameters", fn.path("parameters"));
                }
                if (fn.has("strict")) {
                    cfn.set("strict", fn.path("strict"));
                }
            }
        }
        JsonNode toolChoice = req.path("tool_choice");
        if (toolChoice.isTextual()) {
            chat.set("tool_choice", toolChoice);
        } else if (toolChoice.isObject()) {
            String tcType = toolChoice.path("type").asText("");
            if ("function".equals(tcType)) {
                ObjectNode tc = chat.putObject("tool_choice");
                tc.put("type", "function");
                tc.putObject("function").put("name", toolChoice.path("name").asText(""));
            } else if (!tcType.isEmpty()) {
                throw new BadRequestException("tool_choice type '" + tcType + "' is not supported");
            }
        }

        // text.format -> response_format
        JsonNode format = req.path("text").path("format");
        if (format.isObject()) {
            String fmtType = format.path("type").asText("");
            if ("text".equals(fmtType) || "json_object".equals(fmtType)) {
                ObjectNode rf = chat.putObject("response_format");
                rf.put("type", fmtType);
            } else if ("json_schema".equals(fmtType)) {
                ObjectNode rf = chat.putObject("response_format");
                rf.put("type", "json_schema");
                ObjectNode js = rf.putObject("json_schema");
                JsonNode src = format.path("json_schema");
                js.put("name", src.path("name").asText("response"));
                if (src.has("schema")) {
                    js.set("schema", src.path("schema"));
                }
                if (src.has("strict")) {
                    js.set("strict", src.path("strict"));
                }
            }
        }

        boolean stream = req.path("stream").asBoolean(false);
        if (stream) {
            chat.put("stream", true);
            chat.putObject("stream_options").put("include_usage", true);
        }
        return objectMapper.writeValueAsBytes(chat);
    }

    private void appendInputItem(ArrayNode messages, JsonNode item) {
        String type = item.path("type").asText(item.has("role") ? "message" : "");
        switch (type) {
            case "message" -> {
                String role = item.path("role").asText("user");
                if ("developer".equals(role)) {
                    role = "system";
                }
                ObjectNode msg = messages.addObject();
                msg.put("role", role);
                JsonNode content = item.path("content");
                if (content.isTextual()) {
                    msg.put("content", content.asText());
                } else if (content.isArray()) {
                    msg.set("content", mapContentParts(content));
                } else {
                    msg.put("content", "");
                }
            }
            case "function_call" -> {
                ObjectNode msg = messages.addObject();
                msg.put("role", "assistant");
                ArrayNode toolCalls = msg.putArray("tool_calls");
                ObjectNode tc = toolCalls.addObject();
                tc.put("id", item.path("call_id").asText(""));
                tc.put("type", "function");
                ObjectNode fn = tc.putObject("function");
                fn.put("name", item.path("name").asText(""));
                fn.put("arguments", item.path("arguments").asText(""));
            }
            case "function_call_output" -> {
                ObjectNode msg = messages.addObject();
                msg.put("role", "tool");
                msg.put("tool_call_id", item.path("call_id").asText(""));
                msg.put("content", extractText(item.path("output")));
            }
            case "reasoning", "item_reference" -> {
                // 无状态适配：推理项与引用项不回传给 chat 上游
            }
            default -> throw new BadRequestException("unsupported input item type '" + type + "'");
        }
    }

    /** content parts：全文本时折叠为字符串（兼容性最好），含图片则用 chat content 数组 */
    private JsonNode mapContentParts(JsonNode parts) {
        StringBuilder text = new StringBuilder();
        List<JsonNode> images = new ArrayList<>();
        for (JsonNode part : parts) {
            String ptype = part.path("type").asText("");
            switch (ptype) {
                case "input_text", "output_text", "text" -> text.append(part.path("text").asText(""));
                case "input_image" -> {
                    String url = part.path("image_url").asText(part.path("url").asText(""));
                    if (!url.isEmpty()) {
                        ObjectNode img = objectMapper.createObjectNode();
                        img.put("type", "image_url");
                        img.putObject("image_url").put("url", url);
                        images.add(img);
                    }
                }
                default -> {
                    // input_file 等其余 part 忽略
                }
            }
        }
        if (images.isEmpty()) {
            return objectMapper.getNodeFactory().textNode(text.toString());
        }
        ArrayNode arr = objectMapper.createArrayNode();
        if (!text.isEmpty()) {
            arr.addObject().put("type", "text").put("text", text.toString());
        }
        for (JsonNode img : images) {
            arr.add(img);
        }
        return arr;
    }

    private String extractText(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : node) {
                if (part.isTextual()) {
                    sb.append(part.asText());
                } else if (part.has("text")) {
                    sb.append(part.path("text").asText(""));
                }
            }
            return sb.toString();
        }
        return node.isMissingNode() || node.isNull() ? "" : node.toString();
    }

    private void copyNumber(ObjectNode target, JsonNode source, String field) {
        if (source.path(field).isNumber()) {
            target.set(field, source.path(field));
        }
    }

    // ---------- 响应：Chat -> Responses（非流式） ----------

    public ObjectNode toResponsesResponse(JsonNode chatResp, String model) {
        String respId = "resp_" + compactId();
        long createdAt = System.currentTimeMillis() / 1000;
        ArrayNode output = objectMapper.createArrayNode();

        JsonNode choice = chatResp.path("choices").path(0);
        JsonNode message = choice.path("message");
        String finishReason = choice.path("finish_reason").asText(null);

        JsonNode toolCalls = message.path("tool_calls");
        int fcIndex = 0;
        if (toolCalls.isArray()) {
            for (JsonNode tc : toolCalls) {
                ObjectNode fc = output.addObject();
                fc.put("type", "function_call");
                fc.put("id", "fc_" + compactId());
                fc.put("call_id", tc.path("id").asText("call_" + fcIndex));
                fc.put("name", tc.path("function").path("name").asText(""));
                fc.put("arguments", tc.path("function").path("arguments").asText(""));
                fc.put("status", "completed");
                fcIndex++;
            }
        }
        String content = message.path("content").asText("");
        if (!content.isEmpty()) {
            output.add(buildMessageItem("msg_" + compactId(), content, "completed"));
        }

        CallRecorder.Usage usage = null;
        JsonNode usageNode = chatResp.path("usage");
        if (usageNode.isObject()) {
            usage = new CallRecorder.Usage();
            usage.prompt = usageNode.path("prompt_tokens").asInt(0);
            usage.completion = usageNode.path("completion_tokens").asInt(0);
            usage.total = usageNode.path("total_tokens").asInt(0);
            usage.cached = usageNode.path("prompt_tokens_details").path("cached_tokens").asInt(0);
        }
        String effectiveModel = chatResp.path("model").asText(model);
        return buildResponse(respId, effectiveModel, createdAt,
                "length".equals(finishReason) ? "incomplete" : "completed",
                output, usage, "length".equals(finishReason) ? "max_output_tokens" : null);
    }

    // ---------- 流式状态机 ----------

    /**
     * 每个流式请求一个实例：输入 chat chunk JSON，输出 Responses SSE 事件串。
     * 事件顺序：created → in_progress → (output_item.added / content_part.added / *.delta)* →
     * 收尾：*.done / output_item.done → completed → data: [DONE]
     */
    public class StreamTranslator {

        private final String respId = "resp_" + compactId();
        private final String model;
        private final long createdAt = System.currentTimeMillis() / 1000;

        public StreamTranslator(String model) {
            this.model = model == null ? "unknown" : model;
        }

        private int seq = 0;
        private int nextOutputIndex = 0;

        private boolean textOpen;
        private String textItemId;
        private int textOutputIndex;
        private final StringBuilder fullText = new StringBuilder();

        private final Map<Integer, ToolCallState> toolCalls = new LinkedHashMap<>();

        private String finishReason;

        private class ToolCallState {
            String itemId;
            String callId;
            final StringBuilder name = new StringBuilder();
            final StringBuilder arguments = new StringBuilder();
            int outputIndex;
        }

        public String start() {
            StringBuilder sb = new StringBuilder();
            ObjectNode skeleton = objectMapper.createObjectNode();
            skeleton.put("id", respId);
            skeleton.put("object", "response");
            skeleton.put("created_at", createdAt);
            skeleton.put("status", "in_progress");
            skeleton.put("model", model);
            skeleton.putArray("output");
            sb.append(renderEvent("response.created", withResponse(skeleton)));
            ObjectNode skeleton2 = skeleton.deepCopy();
            sb.append(renderEvent("response.in_progress", withResponse(skeleton2)));
            return sb.toString();
        }

        public String onChunk(String payloadJson) {
            JsonNode chunk;
            try {
                chunk = objectMapper.readTree(payloadJson);
            } catch (Exception e) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            JsonNode choice = chunk.path("choices").path(0);
            JsonNode delta = choice.path("delta");

            String content = delta.path("content").asText(null);
            if (content != null && !content.isEmpty()) {
                if (!textOpen) {
                    textOpen = true;
                    textItemId = "msg_" + compactId();
                    textOutputIndex = nextOutputIndex++;
                    ObjectNode item = objectMapper.createObjectNode();
                    item.put("id", textItemId);
                    item.put("type", "message");
                    item.put("role", "assistant");
                    item.put("status", "in_progress");
                    item.putArray("content");
                    sb.append(renderEvent("response.output_item.added", itemEvent(textOutputIndex, item)));
                    ObjectNode part = objectMapper.createObjectNode();
                    part.put("type", "output_text");
                    part.put("text", "");
                    part.putArray("annotations");
                    ObjectNode ev = objectMapper.createObjectNode();
                    ev.put("item_id", textItemId);
                    ev.put("output_index", textOutputIndex);
                    ev.put("content_index", 0);
                    ev.set("part", part);
                    sb.append(renderEvent("response.content_part.added", ev));
                }
                ObjectNode ev = objectMapper.createObjectNode();
                ev.put("item_id", textItemId);
                ev.put("output_index", textOutputIndex);
                ev.put("content_index", 0);
                ev.put("delta", content);
                sb.append(renderEvent("response.output_text.delta", ev));
                fullText.append(content);
            }

            JsonNode tcs = delta.path("tool_calls");
            if (tcs.isArray()) {
                for (JsonNode tc : tcs) {
                    int index = tc.path("index").asInt(0);
                    ToolCallState state = toolCalls.get(index);
                    if (state == null) {
                        state = new ToolCallState();
                        state.itemId = "fc_" + compactId();
                        state.callId = tc.path("id").asText("");
                        if (state.callId.isEmpty()) {
                            state.callId = "call_" + compactId();
                        }
                        state.outputIndex = nextOutputIndex++;
                        toolCalls.put(index, state);

                        ObjectNode item = objectMapper.createObjectNode();
                        item.put("id", state.itemId);
                        item.put("type", "function_call");
                        item.put("call_id", state.callId);
                        item.put("name", tc.path("function").path("name").asText(""));
                        item.put("arguments", "");
                        item.put("status", "in_progress");
                        sb.append(renderEvent("response.output_item.added", itemEvent(state.outputIndex, item)));
                    }
                    String id = tc.path("id").asText(null);
                    if (id != null && !id.isEmpty()) {
                        state.callId = id;
                    }
                    String name = tc.path("function").path("name").asText(null);
                    if (name != null) {
                        state.name.append(name);
                    }
                    String args = tc.path("function").path("arguments").asText(null);
                    if (args != null && !args.isEmpty()) {
                        ObjectNode ev = objectMapper.createObjectNode();
                        ev.put("item_id", state.itemId);
                        ev.put("output_index", state.outputIndex);
                        ev.put("delta", args);
                        sb.append(renderEvent("response.function_call_arguments.delta", ev));
                        state.arguments.append(args);
                    }
                }
            }

            String fr = choice.path("finish_reason").asText(null);
            if (fr != null && !fr.isEmpty()) {
                finishReason = fr;
            }
            return sb.toString();
        }

        public String finish(CallRecorder.Usage usage) {
            StringBuilder sb = new StringBuilder();
            List<ObjectNode> finalOutput = new ArrayList<>();
            Object[] ordered = new Object[nextOutputIndex];
            if (textOpen) {
                ordered[textOutputIndex] = new Object[]{textOutputIndex, "text"};
            }
            for (ToolCallState st : toolCalls.values()) {
                ordered[st.outputIndex] = new Object[]{st.outputIndex, "tool", st};
            }
            for (Object entry : ordered) {
                if (entry == null) {
                    continue;
                }
                Object[] e = (Object[]) entry;
                if ("text".equals(e[1])) {
                    ObjectNode part = objectMapper.createObjectNode();
                    part.put("type", "output_text");
                    part.put("text", fullText.toString());
                    part.putArray("annotations");

                    ObjectNode doneEv = objectMapper.createObjectNode();
                    doneEv.put("item_id", textItemId);
                    doneEv.put("output_index", textOutputIndex);
                    doneEv.put("content_index", 0);
                    doneEv.put("text", fullText.toString());
                    sb.append(renderEvent("response.output_text.done", doneEv));

                    ObjectNode partEv = objectMapper.createObjectNode();
                    partEv.put("item_id", textItemId);
                    partEv.put("output_index", textOutputIndex);
                    partEv.put("content_index", 0);
                    partEv.set("part", part);
                    sb.append(renderEvent("response.content_part.done", partEv));

                    ObjectNode item = buildMessageItem(textItemId, fullText.toString(), "completed");
                    sb.append(renderEvent("response.output_item.done", itemEvent(textOutputIndex, item)));
                    finalOutput.add(item);
                } else {
                    ToolCallState st = (ToolCallState) e[2];
                    ObjectNode doneEv = objectMapper.createObjectNode();
                    doneEv.put("item_id", st.itemId);
                    doneEv.put("output_index", st.outputIndex);
                    doneEv.put("arguments", st.arguments.toString());
                    sb.append(renderEvent("response.function_call_arguments.done", doneEv));

                    ObjectNode item = objectMapper.createObjectNode();
                    item.put("id", st.itemId);
                    item.put("type", "function_call");
                    item.put("call_id", st.callId);
                    item.put("name", st.name.toString());
                    item.put("arguments", st.arguments.toString());
                    item.put("status", "completed");
                    sb.append(renderEvent("response.output_item.done", itemEvent(st.outputIndex, item)));
                    finalOutput.add(item);
                }
            }

            boolean incomplete = "length".equals(finishReason);
            ObjectNode response = buildResponse(respId, model, createdAt,
                    incomplete ? "incomplete" : "completed", toArray(finalOutput), usage,
                    incomplete ? "max_output_tokens" : null);
            sb.append(renderEvent("response.completed", withResponse(response)));
            sb.append("data: [DONE]\n\n");
            return sb.toString();
        }

        public String failed(String message) {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("id", respId);
            response.put("object", "response");
            response.put("created_at", createdAt);
            response.put("status", "failed");
            response.put("model", model);
            response.putArray("output");
            ObjectNode err = response.putObject("error");
            err.put("type", "server_error");
            err.put("message", message);
            return renderEvent("response.failed", withResponse(response)) + "data: [DONE]\n\n";
        }

        public String responseId() {
            return respId;
        }

        private String renderEvent(String type, ObjectNode data) {
            data.put("type", type);
            data.put("sequence_number", seq++);
            return "event: " + type + "\ndata: " + data.toString() + "\n\n";
        }
    }

    // ---------- 渲染 helpers ----------

    private ObjectNode buildMessageItem(String id, String text, String status) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("id", id);
        item.put("type", "message");
        item.put("role", "assistant");
        item.put("status", status);
        ArrayNode content = item.putArray("content");
        ObjectNode part = content.addObject();
        part.put("type", "output_text");
        part.put("text", text);
        part.putArray("annotations");
        return item;
    }

    private ObjectNode buildResponse(String id, String model, long createdAt, String status,
                                     ArrayNode output, CallRecorder.Usage usage, String incompleteReason) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", id);
        response.put("object", "response");
        response.put("created_at", createdAt);
        response.put("status", status);
        response.putNull("error");
        if (incompleteReason != null) {
            response.putObject("incomplete_details").put("reason", incompleteReason);
        } else {
            response.putNull("incomplete_details");
        }
        response.put("model", model);
        response.set("output", output);
        response.put("parallel_tool_calls", true);
        response.put("tool_choice", "auto");
        response.putArray("tools");
        if (usage != null) {
            ObjectNode u = response.putObject("usage");
            u.put("input_tokens", usage.prompt == null ? 0 : usage.prompt);
            u.put("output_tokens", usage.completion == null ? 0 : usage.completion);
            u.put("total_tokens", usage.total == null ? 0 : usage.total);
            u.putObject("input_tokens_details").put("cached_tokens", usage.cached == null ? 0 : usage.cached);
            u.putObject("output_tokens_details").put("reasoning_tokens", 0);
        }
        return response;
    }

    private ArrayNode toArray(List<ObjectNode> items) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (ObjectNode item : items) {
            arr.add(item);
        }
        return arr;
    }

    private ObjectNode itemEvent(int outputIndex, ObjectNode item) {
        ObjectNode ev = objectMapper.createObjectNode();
        ev.put("output_index", outputIndex);
        ev.set("item", item);
        return ev;
    }

    private ObjectNode withResponse(ObjectNode response) {
        ObjectNode ev = objectMapper.createObjectNode();
        ev.set("response", response);
        return ev;
    }

    private String compactId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    public byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}