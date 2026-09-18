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
import java.util.Set;
import java.util.UUID;

/**
 * OpenAI Responses API 与上游 Chat Completions API 之间的无状态协议适配器。
 *
 * <p>网关只模拟客户端侧能力：function、custom、namespace 和 client tool_search。
 * web_search 等必须由服务端执行的内置工具无法由 llama-server 代办：工具定义与调用历史
 * 直接忽略（单个托管工具不应导致整个会话 400），只有未知工具类型才显式拒绝。</p>
 */
@Component
@Slf4j
public class ResponsesAdapter {

    private static final int CHAT_TOOL_NAME_MAX_LENGTH = 64;

    /** 必须由 OpenAI 服务端执行的内置/托管工具：上游无法提供，工具定义与调用历史均忽略 */
    private static final Set<String> SERVER_EXECUTED_TOOLS = Set.of(
            "web_search", "web_search_preview", "file_search", "code_interpreter",
            "image_generation", "computer_use_preview", "computer", "mcp",
            "local_shell", "shell", "apply_patch", "programmatic_tool_calling");

    /** 服务端托管调用的历史 item：chat 上游无对应表示，跳过不回传 */
    private static final Set<String> SERVER_EXECUTED_CALL_ITEMS = Set.of(
            "web_search_call", "file_search_call", "code_interpreter_call", "image_generation_call",
            "computer_call", "mcp_call", "mcp_list_tools", "mcp_approval_request",
            "local_shell_call", "shell_call", "apply_patch_call");

    private final ObjectMapper objectMapper;

    public ResponsesAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) {
            super(message);
        }
    }

    public static class UpstreamStreamException extends RuntimeException {
        public UpstreamStreamException(String message) {
            super(message);
        }
    }

    enum ToolKind {
        FUNCTION,
        CUSTOM,
        TOOL_SEARCH
    }

    record ToolDescriptor(ToolKind kind, String namespace, String name, String execution) {
    }

    /** 转换后的 chat 请求，以及把 chat 工具名还原成 Responses 工具所需的请求级索引。 */
    public static final class PreparedChatRequest {
        final byte[] body;
        final Map<String, ToolDescriptor> toolsByChatName;

        PreparedChatRequest(byte[] body, Map<String, ToolDescriptor> toolsByChatName) {
            this.body = body;
            this.toolsByChatName = Map.copyOf(toolsByChatName);
        }
    }

    /** Responses 请求转为 llama-server 的 Chat Completions 请求。 */
    public PreparedChatRequest toChatRequest(byte[] responsesBody) {
        JsonNode request = parseRequest(responsesBody);
        String model = requiredText(request, "model", "field 'model' is required");
        rejectStatefulOptions(request);

        ObjectNode chat = objectMapper.createObjectNode();
        chat.put("model", model);

        ToolRegistry toolRegistry = new ToolRegistry();
        mapTools(request.path("tools"), chat, toolRegistry);

        ArrayNode messages = chat.putArray("messages");
        appendSystemMessage(request, messages);
        appendInput(request.path("input"), messages, toolRegistry);
        if (messages.isEmpty()) {
            throw new BadRequestException("field 'input' must contain at least one message");
        }

        copyNumber(chat, request, "temperature");
        copyNumber(chat, request, "top_p");
        if (request.path("max_output_tokens").isIntegralNumber()) {
            chat.set("max_tokens", request.path("max_output_tokens"));
        }
        JsonNode stop = request.path("stop");
        if (stop.isTextual() || stop.isArray()) {
            chat.set("stop", stop);
        }
        if (request.path("parallel_tool_calls").isBoolean()) {
            chat.set("parallel_tool_calls", request.path("parallel_tool_calls"));
        }

        mapToolChoice(request.path("tool_choice"), chat, toolRegistry);
        mapTextFormat(request.path("text").path("format"), chat);

        if (request.path("stream").asBoolean(false)) {
            chat.put("stream", true);
            chat.putObject("stream_options").put("include_usage", true);
        }

        return new PreparedChatRequest(objectMapper.writeValueAsBytes(chat), toolRegistry.byChatName);
    }

    private JsonNode parseRequest(byte[] body) {
        if (body == null || body.length == 0) {
            throw new BadRequestException("request body is required");
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new BadRequestException("request body must be a JSON object");
            }
            return request;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("request body is not valid JSON");
        }
    }

    private void rejectStatefulOptions(JsonNode request) {
        if (request.has("previous_response_id") && !request.path("previous_response_id").isNull()) {
            throw new BadRequestException("'previous_response_id' is not supported: this gateway is stateless; send the full conversation in 'input'");
        }
        if (request.path("background").asBoolean(false)) {
            throw new BadRequestException("'background' mode is not supported");
        }
        if (request.has("conversation") && !request.path("conversation").isNull()) {
            throw new BadRequestException("'conversation' is not supported: this gateway is stateless");
        }
    }

    private void mapTools(JsonNode tools, ObjectNode chat, ToolRegistry registry) {
        if (tools.isMissingNode() || tools.isNull()) {
            return;
        }
        if (!tools.isArray()) {
            throw new BadRequestException("field 'tools' must be an array");
        }
        ArrayNode chatTools = objectMapper.createArrayNode();
        for (JsonNode tool : tools) {
            if (!tool.isObject()) {
                throw new BadRequestException("each tool must be a JSON object");
            }
            String type = tool.path("type").asText("function");
            switch (type) {
                case "function" -> addFunctionTool(chatTools, tool, null, registry);
                case "custom" -> addCustomTool(chatTools, tool, null, registry);
                case "namespace" -> addNamespaceTools(chatTools, tool, registry);
                case "tool_search" -> addToolSearch(chatTools, tool, registry);
                default -> {
                    if (SERVER_EXECUTED_TOOLS.contains(type)) {
                        log.debug("Ignoring server-executed tool '{}': upstream chat completions cannot serve it", type);
                    } else {
                        throw new BadRequestException("tool type '" + type
                                + "' is not supported by the chat-completions upstream");
                    }
                }
            }
        }
        if (!chatTools.isEmpty()) {
            chat.set("tools", chatTools);
        }
    }

    private void addNamespaceTools(ArrayNode chatTools, JsonNode namespaceTool, ToolRegistry registry) {
        String namespace = requiredText(namespaceTool, "name", "namespace tool requires a non-empty 'name'");
        JsonNode innerTools = namespaceTool.path("tools");
        if (!innerTools.isArray() || innerTools.isEmpty()) {
            throw new BadRequestException("namespace tool '" + namespace + "' requires a non-empty 'tools' array");
        }
        for (JsonNode innerTool : innerTools) {
            String type = innerTool.path("type").asText("function");
            switch (type) {
                case "function" -> addFunctionTool(chatTools, innerTool, namespace, registry);
                case "custom" -> addCustomTool(chatTools, innerTool, namespace, registry);
                default -> throw new BadRequestException("tool type '" + type + "' inside namespace '"
                        + namespace + "' is not supported");
            }
        }
    }

    private void addFunctionTool(ArrayNode chatTools, JsonNode source, String namespace, ToolRegistry registry) {
        JsonNode function = source.path("function").isObject() ? source.path("function") : source;
        String name = requiredText(function, "name", "function tool requires a non-empty 'name'");
        String chatName = registry.register(ToolKind.FUNCTION, namespace, name, null);

        ObjectNode target = chatTools.addObject();
        target.put("type", "function");
        ObjectNode targetFunction = target.putObject("function");
        targetFunction.put("name", chatName);
        copyIfPresent(function, targetFunction, "description");
        copyIfPresent(function, targetFunction, "parameters");
        copyIfPresent(function, targetFunction, "strict");
    }

    /**
     * Chat Completions 没有 free-form custom tool。这里用一个只有 input 字符串参数的函数承载，
     * 返回时再还原为 custom_tool_call，避免 Codex 把 apply_patch 等工具误当普通 function。
     */
    private void addCustomTool(ArrayNode chatTools, JsonNode source, String namespace, ToolRegistry registry) {
        String name = requiredText(source, "name", "custom tool requires a non-empty 'name'");
        String chatName = registry.register(ToolKind.CUSTOM, namespace, name, null);

        ObjectNode target = chatTools.addObject();
        target.put("type", "function");
        ObjectNode function = target.putObject("function");
        function.put("name", chatName);

        String description = source.path("description").asText("");
        StringBuilder adaptedDescription = new StringBuilder(description);
        if (!description.isBlank()) {
            adaptedDescription.append("\n\n");
        }
        adaptedDescription.append("Pass the complete free-form tool input in the `input` string field.");
        JsonNode format = source.path("format");
        if (format.isObject() && "grammar".equals(format.path("type").asText())
                && format.path("definition").isTextual()) {
            adaptedDescription.append(" The input must follow this ")
                    .append(format.path("syntax").asText("custom"))
                    .append(" grammar:\n")
                    .append(format.path("definition").asText());
        }
        function.put("description", adaptedDescription.toString());
        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        parameters.putObject("properties").putObject("input").put("type", "string");
        parameters.putArray("required").add("input");
        parameters.put("additionalProperties", false);
    }

    private void addToolSearch(ArrayNode chatTools, JsonNode source, ToolRegistry registry) {
        String execution = source.path("execution").asText("client");
        if (!"client".equals(execution) && !"sync".equals(execution)) {
            throw new BadRequestException("only client-executed tool_search is supported");
        }
        String chatName = registry.register(ToolKind.TOOL_SEARCH, null, "tool_search", execution);
        ObjectNode target = chatTools.addObject();
        target.put("type", "function");
        ObjectNode function = target.putObject("function");
        function.put("name", chatName);
        function.put("description", source.path("description").asText("Search for additional tools"));
        if (source.path("parameters").isObject()) {
            function.set("parameters", source.path("parameters"));
        } else {
            function.putObject("parameters").put("type", "object");
        }
    }

    private void appendSystemMessage(JsonNode request, ArrayNode messages) {
        List<String> parts = new ArrayList<>();
        collectInstructionText(request.path("instructions"), parts);
        JsonNode input = request.path("input");
        if (input.isArray()) {
            for (JsonNode item : input) {
                if (isSystemMessage(item)) {
                    String text = extractText(item.path("content"));
                    if (!text.isBlank()) {
                        parts.add(text);
                    }
                }
            }
        }
        if (!parts.isEmpty()) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", String.join("\n\n", parts));
        }
    }

    private void collectInstructionText(JsonNode instructions, List<String> parts) {
        if (instructions.isTextual()) {
            if (!instructions.asText().isBlank()) {
                parts.add(instructions.asText());
            }
            return;
        }
        if (instructions.isArray()) {
            for (JsonNode item : instructions) {
                String text = item.has("content") ? extractText(item.path("content")) : extractText(item);
                if (!text.isBlank()) {
                    parts.add(text);
                }
            }
        }
    }

    private void appendInput(JsonNode input, ArrayNode messages, ToolRegistry registry) {
        if (input.isTextual()) {
            messages.addObject().put("role", "user").put("content", input.asText());
            return;
        }
        if (input.isMissingNode() || input.isNull()) {
            return;
        }
        if (!input.isArray()) {
            throw new BadRequestException("field 'input' must be a string or an array");
        }

        ObjectNode pendingAssistant = null;
        for (JsonNode item : input) {
            String type = inputItemType(item);
            if (isSystemMessage(item) || "reasoning".equals(type) || "item_reference".equals(type)
                    || SERVER_EXECUTED_CALL_ITEMS.contains(type)) {
                continue;
            }
            if (isToolCall(type)) {
                if (pendingAssistant == null) {
                    pendingAssistant = objectMapper.createObjectNode();
                    pendingAssistant.put("role", "assistant");
                    pendingAssistant.putNull("content");
                    pendingAssistant.putArray("tool_calls");
                }
                appendHistoricalToolCall(pendingAssistant.withArray("tool_calls"), item, type, registry);
                continue;
            }

            if (pendingAssistant != null) {
                messages.add(pendingAssistant);
                pendingAssistant = null;
            }
            switch (type) {
                case "message" -> appendMessage(messages, item);
                case "function_call_output", "custom_tool_call_output", "tool_search_output" ->
                        appendToolOutput(messages, item, type);
                default -> throw new BadRequestException("unsupported input item type '" + type + "'");
            }
        }
        if (pendingAssistant != null) {
            messages.add(pendingAssistant);
        }
    }

    private boolean isToolCall(String type) {
        return "function_call".equals(type) || "custom_tool_call".equals(type)
                || "tool_search_call".equals(type);
    }

    private void appendMessage(ArrayNode messages, JsonNode item) {
        String role = item.path("role").asText("user");
        if (!"user".equals(role) && !"assistant".equals(role)) {
            throw new BadRequestException("message role '" + role + "' is not supported in input");
        }
        ObjectNode message = messages.addObject();
        message.put("role", role);
        JsonNode content = item.path("content");
        if (content.isTextual()) {
            message.put("content", content.asText());
        } else if (content.isArray()) {
            message.set("content", mapContentParts(content));
        } else {
            message.put("content", "");
        }
    }

    private void appendHistoricalToolCall(ArrayNode toolCalls, JsonNode item, String type,
                                          ToolRegistry registry) {
        ToolKind kind = switch (type) {
            case "custom_tool_call" -> ToolKind.CUSTOM;
            case "tool_search_call" -> ToolKind.TOOL_SEARCH;
            default -> ToolKind.FUNCTION;
        };
        String namespace = nullableText(item.path("namespace"));
        String name = kind == ToolKind.TOOL_SEARCH ? "tool_search"
                : requiredText(item, "name", type + " requires a non-empty 'name'");
        String chatName = registry.findChatName(kind, namespace, name);
        if (chatName == null) {
            chatName = safeChatName(namespace, name);
        }

        ObjectNode toolCall = toolCalls.addObject();
        toolCall.put("id", requiredText(item, "call_id", type + " requires a non-empty 'call_id'"));
        toolCall.put("type", "function");
        ObjectNode function = toolCall.putObject("function");
        function.put("name", chatName);
        if (kind == ToolKind.CUSTOM) {
            ObjectNode arguments = objectMapper.createObjectNode();
            arguments.put("input", item.path("input").asText(""));
            function.put("arguments", arguments.toString());
        } else if (kind == ToolKind.TOOL_SEARCH) {
            JsonNode arguments = item.path("arguments");
            function.put("arguments", arguments.isTextual() ? arguments.asText() : arguments.toString());
        } else {
            function.put("arguments", item.path("arguments").asText("{}"));
        }
    }

    private void appendToolOutput(ArrayNode messages, JsonNode item, String type) {
        String callId = requiredText(item, "call_id", type + " requires a non-empty 'call_id'");
        ObjectNode message = messages.addObject();
        message.put("role", "tool");
        message.put("tool_call_id", callId);
        if ("tool_search_output".equals(type)) {
            message.put("content", item.path("tools").isArray() ? item.path("tools").toString() : "[]");
            return;
        }
        JsonNode output = item.path("output");
        if (output.isArray()) {
            message.set("content", mapContentParts(output));
        } else {
            message.put("content", extractText(output));
        }
    }

    /** 全文本折叠为字符串；包含图片时保留原顺序并转换成 chat 多模态 content。 */
    private JsonNode mapContentParts(JsonNode parts) {
        boolean hasImage = false;
        for (JsonNode part : parts) {
            if ("input_image".equals(part.path("type").asText())) {
                hasImage = true;
                break;
            }
        }
        if (!hasImage) {
            return objectMapper.getNodeFactory().textNode(extractText(parts));
        }

        ArrayNode content = objectMapper.createArrayNode();
        for (JsonNode part : parts) {
            String type = part.path("type").asText("");
            switch (type) {
                case "input_text", "output_text", "text", "refusal" -> {
                    String text = part.path("text").asText(part.path("refusal").asText(""));
                    if (!text.isEmpty()) {
                        content.addObject().put("type", "text").put("text", text);
                    }
                }
                case "input_image" -> {
                    String url = part.path("image_url").asText(part.path("url").asText(""));
                    if (url.isEmpty()) {
                        throw new BadRequestException("input_image requires 'image_url'; file_id images are not supported");
                    }
                    ObjectNode image = content.addObject();
                    image.put("type", "image_url");
                    ObjectNode imageUrl = image.putObject("image_url");
                    imageUrl.put("url", url);
                    if (part.path("detail").isTextual()) {
                        imageUrl.set("detail", part.path("detail"));
                    }
                }
                default -> throw new BadRequestException("content part type '" + type + "' is not supported");
            }
        }
        return content;
    }

    private void mapToolChoice(JsonNode choice, ObjectNode chat, ToolRegistry registry) {
        if (choice.isMissingNode() || choice.isNull()) {
            return;
        }
        if (choice.isTextual()) {
            String value = choice.asText();
            if (!"auto".equals(value) && !"none".equals(value) && !"required".equals(value)) {
                throw new BadRequestException("tool_choice '" + value + "' is not supported");
            }
            chat.put("tool_choice", value);
            return;
        }
        if (!choice.isObject()) {
            throw new BadRequestException("field 'tool_choice' must be a string or object");
        }

        String type = choice.path("type").asText("");
        ToolKind kind = switch (type) {
            case "function" -> ToolKind.FUNCTION;
            case "custom" -> ToolKind.CUSTOM;
            case "tool_search" -> ToolKind.TOOL_SEARCH;
            default -> throw new BadRequestException("tool_choice type '" + type + "' is not supported");
        };
        String namespace = nullableText(choice.path("namespace"));
        String name = kind == ToolKind.TOOL_SEARCH ? "tool_search"
                : requiredText(choice, "name", "tool_choice requires a non-empty 'name'");
        String chatName = registry.findChatName(kind, namespace, name);
        if (chatName == null) {
            throw new BadRequestException("tool_choice references an unknown tool '" + name + "'");
        }
        ObjectNode target = chat.putObject("tool_choice");
        target.put("type", "function");
        target.putObject("function").put("name", chatName);
    }

    private void mapTextFormat(JsonNode format, ObjectNode chat) {
        if (format.isMissingNode() || format.isNull()) {
            return;
        }
        if (!format.isObject()) {
            throw new BadRequestException("field 'text.format' must be an object");
        }
        String type = format.path("type").asText("text");
        switch (type) {
            case "text" -> chat.putObject("response_format").put("type", "text");
            case "json_object" -> chat.putObject("response_format").put("type", "json_object");
            case "json_schema" -> {
                JsonNode source = format.path("json_schema").isObject() ? format.path("json_schema") : format;
                JsonNode schema = source.path("schema");
                if (!schema.isObject()) {
                    throw new BadRequestException("text.format type 'json_schema' requires a 'schema' object");
                }
                ObjectNode responseFormat = chat.putObject("response_format");
                responseFormat.put("type", "json_schema");
                ObjectNode jsonSchema = responseFormat.putObject("json_schema");
                jsonSchema.put("name", source.path("name").asText("response"));
                jsonSchema.set("schema", schema);
                if (source.path("strict").isBoolean()) {
                    jsonSchema.set("strict", source.path("strict"));
                }
            }
            default -> throw new BadRequestException("text format type '" + type + "' is not supported by the upstream");
        }
    }

    /** Chat Completions 非流式响应转为 Responses 响应。 */
    public ObjectNode toResponsesResponse(JsonNode chatResponse, String requestedModel,
                                          Map<String, ToolDescriptor> toolsByChatName) {
        JsonNode choice = chatResponse.path("choices").path(0);
        if (!choice.isObject() || !choice.path("message").isObject()) {
            throw new UpstreamStreamException("upstream returned no chat completion choice");
        }

        String responseId = "resp_" + compactId();
        long createdAt = chatResponse.path("created").asLong(System.currentTimeMillis() / 1000);
        ArrayNode output = objectMapper.createArrayNode();
        JsonNode message = choice.path("message");

        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) {
            int fallbackIndex = 0;
            for (JsonNode toolCall : toolCalls) {
                String chatName = toolCall.path("function").path("name").asText("");
                String callId = toolCall.path("id").asText("call_" + fallbackIndex++);
                String arguments = toolCall.path("function").path("arguments").asText("");
                output.add(buildToolItem("fc_" + compactId(), callId, chatName, arguments,
                        "completed", toolsByChatName));
            }
        }

        String content = assistantText(message.path("content"));
        if (!content.isEmpty()) {
            output.add(buildMessageItem("msg_" + compactId(), content, "completed"));
        }

        CallRecorder.Usage usage = parseChatUsage(chatResponse.path("usage"));
        String finishReason = nullableText(choice.path("finish_reason"));
        String incompleteReason = incompleteReason(finishReason);
        String effectiveModel = chatResponse.path("model").asText(requestedModel);
        return buildResponse(responseId, effectiveModel, createdAt,
                incompleteReason == null ? "completed" : "incomplete", output, usage, incompleteReason);
    }

    private CallRecorder.Usage parseChatUsage(JsonNode usageNode) {
        if (!usageNode.isObject()) {
            return null;
        }
        CallRecorder.Usage usage = new CallRecorder.Usage();
        usage.prompt = integerOrNull(usageNode.path("prompt_tokens"));
        usage.completion = integerOrNull(usageNode.path("completion_tokens"));
        usage.total = integerOrNull(usageNode.path("total_tokens"));
        usage.cached = integerOrNull(usageNode.path("prompt_tokens_details").path("cached_tokens"));
        return usage;
    }

    /** 每个上游 chat SSE 流使用一个实例。 */
    public final class StreamTranslator {

        private final String responseId = "resp_" + compactId();
        private String model;
        private final long createdAt = System.currentTimeMillis() / 1000;
        private final Map<String, ToolDescriptor> toolsByChatName;
        private final Map<Integer, ToolCallState> toolCalls = new LinkedHashMap<>();

        private int sequenceNumber;
        private int nextOutputIndex;
        private boolean textOpen;
        private String textItemId;
        private int textOutputIndex;
        private final StringBuilder fullText = new StringBuilder();
        private String finishReason;
        private boolean terminal;

        public StreamTranslator(String model, Map<String, ToolDescriptor> toolsByChatName) {
            this.model = model == null ? "unknown" : model;
            this.toolsByChatName = toolsByChatName == null ? Map.of() : toolsByChatName;
        }

        private final class ToolCallState {
            String itemId = "fc_" + compactId();
            String callId = "call_" + compactId();
            StringBuilder chatName = new StringBuilder();
            StringBuilder arguments = new StringBuilder();
            int outputIndex = -1;
            boolean added;

            ToolDescriptor descriptor() {
                return toolsByChatName.get(chatName.toString());
            }
        }

        public String start() {
            ObjectNode skeleton = buildResponse(responseId, model, createdAt, "in_progress",
                    objectMapper.createArrayNode(), null, null);
            return renderEvent("response.created", withResponse(skeleton))
                    + renderEvent("response.in_progress", withResponse(skeleton.deepCopy()));
        }

        public String onChunk(String payloadJson) {
            if (terminal) {
                return "";
            }
            JsonNode chunk;
            try {
                chunk = objectMapper.readTree(payloadJson);
            } catch (Exception e) {
                throw new UpstreamStreamException("upstream sent invalid SSE JSON");
            }
            if (chunk.path("error").isObject()) {
                throw new UpstreamStreamException(upstreamErrorMessage(chunk.path("error")));
            }
            if (chunk.path("model").isTextual()) {
                model = chunk.path("model").asText();
            }

            JsonNode choice = chunk.path("choices").path(0);
            if (!choice.isObject()) {
                return "";
            }
            JsonNode delta = choice.path("delta");
            StringBuilder events = new StringBuilder();

            String content = assistantText(delta.path("content"));
            if (!content.isEmpty()) {
                openText(events);
                ObjectNode event = objectMapper.createObjectNode();
                event.put("item_id", textItemId);
                event.put("output_index", textOutputIndex);
                event.put("content_index", 0);
                event.put("delta", content);
                events.append(renderEvent("response.output_text.delta", event));
                fullText.append(content);
            }

            JsonNode calls = delta.path("tool_calls");
            if (calls.isArray()) {
                for (JsonNode call : calls) {
                    int index = call.path("index").asInt(0);
                    ToolCallState state = toolCalls.computeIfAbsent(index, ignored -> new ToolCallState());
                    String id = nullableText(call.path("id"));
                    if (id != null) {
                        state.callId = id;
                    }
                    String nameDelta = nullableText(call.path("function").path("name"));
                    if (nameDelta != null) {
                        state.chatName.append(nameDelta);
                    }
                    JsonNode argumentsNode = call.path("function").path("arguments");
                    String argumentDelta = argumentsNode.isTextual() ? argumentsNode.asText() : null;
                    if (argumentDelta != null && !argumentDelta.isEmpty()) {
                        ensureToolAdded(state, events);
                        state.arguments.append(argumentDelta);
                        if (toolKind(state) == ToolKind.FUNCTION) {
                            ObjectNode event = objectMapper.createObjectNode();
                            event.put("item_id", state.itemId);
                            event.put("output_index", state.outputIndex);
                            event.put("delta", argumentDelta);
                            events.append(renderEvent("response.function_call_arguments.delta", event));
                        }
                    }
                }
            }

            String reason = nullableText(choice.path("finish_reason"));
            if (reason != null) {
                finishReason = reason;
            }
            return events.toString();
        }

        private void openText(StringBuilder events) {
            if (textOpen) {
                return;
            }
            textOpen = true;
            textItemId = "msg_" + compactId();
            textOutputIndex = nextOutputIndex++;

            ObjectNode item = objectMapper.createObjectNode();
            item.put("id", textItemId);
            item.put("type", "message");
            item.put("role", "assistant");
            item.put("status", "in_progress");
            item.putArray("content");
            events.append(renderEvent("response.output_item.added", itemEvent(textOutputIndex, item)));

            ObjectNode part = objectMapper.createObjectNode();
            part.put("type", "output_text");
            part.put("text", "");
            part.putArray("annotations");
            ObjectNode event = objectMapper.createObjectNode();
            event.put("item_id", textItemId);
            event.put("output_index", textOutputIndex);
            event.put("content_index", 0);
            event.set("part", part);
            events.append(renderEvent("response.content_part.added", event));
        }

        /** 工具名可能跨 chunk，等到首段 arguments 到达后才发 added，避免把半个名字发给 Codex。 */
        private void ensureToolAdded(ToolCallState state, StringBuilder events) {
            if (state.added) {
                return;
            }
            if (state.chatName.isEmpty()) {
                throw new UpstreamStreamException("upstream tool call is missing a function name");
            }
            state.outputIndex = nextOutputIndex++;
            state.added = true;
            ObjectNode item = buildToolItem(state.itemId, state.callId, state.chatName.toString(), "",
                    "in_progress", toolsByChatName);
            events.append(renderEvent("response.output_item.added", itemEvent(state.outputIndex, item)));
        }

        private ToolKind toolKind(ToolCallState state) {
            ToolDescriptor descriptor = state.descriptor();
            return descriptor == null ? ToolKind.FUNCTION : descriptor.kind();
        }

        public String finish(CallRecorder.Usage usage) {
            if (terminal) {
                return "";
            }
            StringBuilder events = new StringBuilder();
            for (ToolCallState state : toolCalls.values()) {
                ensureToolAdded(state, events);
            }
            terminal = true;

            List<ObjectNode> finalOutput = new ArrayList<>();
            Object[] ordered = new Object[nextOutputIndex];
            if (textOpen) {
                ordered[textOutputIndex] = "text";
            }
            for (ToolCallState state : toolCalls.values()) {
                ordered[state.outputIndex] = state;
            }

            for (Object entry : ordered) {
                if ("text".equals(entry)) {
                    finishText(events, finalOutput);
                } else if (entry instanceof ToolCallState state) {
                    finishTool(state, events, finalOutput);
                }
            }

            String reason = incompleteReason(finishReason);
            ObjectNode response = buildResponse(responseId, model, createdAt,
                    reason == null ? "completed" : "incomplete", toArray(finalOutput), usage, reason);
            events.append(renderEvent(reason == null ? "response.completed" : "response.incomplete",
                    withResponse(response)));
            return events.toString();
        }

        private void finishText(StringBuilder events, List<ObjectNode> finalOutput) {
            ObjectNode part = objectMapper.createObjectNode();
            part.put("type", "output_text");
            part.put("text", fullText.toString());
            part.putArray("annotations");

            ObjectNode textDone = objectMapper.createObjectNode();
            textDone.put("item_id", textItemId);
            textDone.put("output_index", textOutputIndex);
            textDone.put("content_index", 0);
            textDone.put("text", fullText.toString());
            events.append(renderEvent("response.output_text.done", textDone));

            ObjectNode partDone = objectMapper.createObjectNode();
            partDone.put("item_id", textItemId);
            partDone.put("output_index", textOutputIndex);
            partDone.put("content_index", 0);
            partDone.set("part", part);
            events.append(renderEvent("response.content_part.done", partDone));

            ObjectNode item = buildMessageItem(textItemId, fullText.toString(), "completed");
            events.append(renderEvent("response.output_item.done", itemEvent(textOutputIndex, item)));
            finalOutput.add(item);
        }

        private void finishTool(ToolCallState state, StringBuilder events, List<ObjectNode> finalOutput) {
            ToolKind kind = toolKind(state);
            String arguments = state.arguments.toString();
            if (kind == ToolKind.FUNCTION) {
                ObjectNode argumentsDone = objectMapper.createObjectNode();
                argumentsDone.put("item_id", state.itemId);
                argumentsDone.put("output_index", state.outputIndex);
                argumentsDone.put("arguments", arguments);
                events.append(renderEvent("response.function_call_arguments.done", argumentsDone));
            } else if (kind == ToolKind.CUSTOM) {
                String input = decodeCustomInput(arguments);
                if (!input.isEmpty()) {
                    ObjectNode inputDelta = objectMapper.createObjectNode();
                    inputDelta.put("item_id", state.itemId);
                    inputDelta.put("output_index", state.outputIndex);
                    inputDelta.put("delta", input);
                    events.append(renderEvent("response.custom_tool_call_input.delta", inputDelta));
                }
                ObjectNode inputDone = objectMapper.createObjectNode();
                inputDone.put("item_id", state.itemId);
                inputDone.put("output_index", state.outputIndex);
                inputDone.put("input", input);
                events.append(renderEvent("response.custom_tool_call_input.done", inputDone));
            }

            ObjectNode item = buildToolItem(state.itemId, state.callId, state.chatName.toString(),
                    arguments, "completed", toolsByChatName);
            events.append(renderEvent("response.output_item.done", itemEvent(state.outputIndex, item)));
            finalOutput.add(item);
        }

        public String failed(String message) {
            if (terminal) {
                return "";
            }
            terminal = true;
            ObjectNode response = buildResponse(responseId, model, createdAt, "failed",
                    objectMapper.createArrayNode(), null, null);
            ObjectNode error = response.putObject("error");
            error.put("type", "server_error");
            error.put("code", "upstream_stream_error");
            error.put("message", message);
            return renderEvent("response.failed", withResponse(response));
        }

        public boolean isTerminal() {
            return terminal;
        }

        private String renderEvent(String type, ObjectNode data) {
            data.put("type", type);
            data.put("sequence_number", sequenceNumber++);
            return "event: " + type + "\ndata: " + data + "\n\n";
        }
    }

    private ObjectNode buildToolItem(String itemId, String callId, String chatName, String arguments,
                                     String status, Map<String, ToolDescriptor> toolsByChatName) {
        ToolDescriptor descriptor = toolsByChatName == null ? null : toolsByChatName.get(chatName);
        ToolKind kind = descriptor == null ? ToolKind.FUNCTION : descriptor.kind();
        ObjectNode item = objectMapper.createObjectNode();
        item.put("id", itemId);
        item.put("call_id", callId);
        item.put("status", status);

        if (kind == ToolKind.CUSTOM) {
            item.put("type", "custom_tool_call");
            item.put("name", descriptor.name());
            if (descriptor.namespace() != null) {
                item.put("namespace", descriptor.namespace());
            }
            item.put("input", decodeCustomInput(arguments));
        } else if (kind == ToolKind.TOOL_SEARCH) {
            item.put("type", "tool_search_call");
            item.put("execution", descriptor.execution() == null ? "client" : descriptor.execution());
            item.set("arguments", parseArguments(arguments));
        } else {
            item.put("type", "function_call");
            item.put("name", descriptor == null ? chatName : descriptor.name());
            if (descriptor != null && descriptor.namespace() != null) {
                item.put("namespace", descriptor.namespace());
            }
            item.put("arguments", arguments);
        }
        return item;
    }

    private JsonNode parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(arguments);
        } catch (Exception e) {
            return objectMapper.getNodeFactory().textNode(arguments);
        }
    }

    private String decodeCustomInput(String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        try {
            JsonNode parsed = objectMapper.readTree(arguments);
            JsonNode input = parsed.path("input");
            if (input.isTextual()) {
                return input.asText();
            }
            if (!input.isMissingNode() && !input.isNull()) {
                return input.toString();
            }
        } catch (Exception ignored) {
            // 某些模型可能直接输出 free-form 文本；原样交还客户端。
        }
        return arguments;
    }

    private ObjectNode buildMessageItem(String id, String text, String status) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("id", id);
        item.put("type", "message");
        item.put("role", "assistant");
        item.put("status", status);
        ObjectNode part = item.putArray("content").addObject();
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
        if ("completed".equals(status) || "incomplete".equals(status) || "failed".equals(status)) {
            response.put("completed_at", System.currentTimeMillis() / 1000);
        } else {
            response.putNull("completed_at");
        }
        response.put("status", status);
        response.putNull("error");
        if (incompleteReason == null) {
            response.putNull("incomplete_details");
        } else {
            response.putObject("incomplete_details").put("reason", incompleteReason);
        }
        response.putNull("instructions");
        response.putNull("max_output_tokens");
        response.put("model", model == null ? "unknown" : model);
        response.set("output", output);
        response.put("parallel_tool_calls", true);
        response.putNull("previous_response_id");
        response.put("store", false);
        response.put("tool_choice", "auto");
        response.putArray("tools");
        response.putObject("text").putObject("format").put("type", "text");
        response.put("truncation", "disabled");
        response.putObject("metadata");
        if (usage != null) {
            ObjectNode target = response.putObject("usage");
            target.put("input_tokens", zeroIfNull(usage.prompt));
            target.put("output_tokens", zeroIfNull(usage.completion));
            target.put("total_tokens", zeroIfNull(usage.total));
            target.putObject("input_tokens_details").put("cached_tokens", zeroIfNull(usage.cached));
            target.putObject("output_tokens_details").put("reasoning_tokens", 0);
        } else {
            response.putNull("usage");
        }
        return response;
    }

    private String incompleteReason(String finishReason) {
        if (finishReason == null || "stop".equals(finishReason) || "tool_calls".equals(finishReason)) {
            return null;
        }
        return switch (finishReason) {
            case "length" -> "max_output_tokens";
            case "content_filter" -> "content_filter";
            default -> "max_output_tokens";
        };
    }

    private String upstreamErrorMessage(JsonNode error) {
        String message = error.path("message").asText("upstream stream error");
        String type = error.path("type").asText("");
        return type.isEmpty() ? message : type + ": " + message;
    }

    private String assistantText(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            return extractText(content);
        }
        return "";
    }

    private String extractText(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : node) {
                if (part.isTextual()) {
                    text.append(part.asText());
                } else if (part.path("text").isTextual()) {
                    text.append(part.path("text").asText());
                } else if (part.path("refusal").isTextual()) {
                    text.append(part.path("refusal").asText());
                }
            }
            return text.toString();
        }
        if (node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.toString();
    }

    private boolean isSystemMessage(JsonNode item) {
        if (!"message".equals(inputItemType(item))) {
            return false;
        }
        String role = item.path("role").asText("");
        return "developer".equals(role) || "system".equals(role);
    }

    private String inputItemType(JsonNode item) {
        return item.path("type").asText(item.has("role") ? "message" : "");
    }

    private String requiredText(JsonNode node, String field, String message) {
        String value = nullableText(node.path(field));
        if (value == null) {
            throw new BadRequestException(message);
        }
        return value;
    }

    private String nullableText(JsonNode node) {
        if (!node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText();
    }

    private void copyNumber(ObjectNode target, JsonNode source, String field) {
        if (source.path(field).isNumber()) {
            target.set(field, source.path(field));
        }
    }

    private void copyIfPresent(JsonNode source, ObjectNode target, String field) {
        if (source.has(field) && !source.path(field).isNull()) {
            target.set(field, source.path(field));
        }
    }

    private Integer integerOrNull(JsonNode value) {
        return value.isIntegralNumber() ? value.asInt() : null;
    }

    private int zeroIfNull(Integer value) {
        return value == null ? 0 : value;
    }

    private ArrayNode toArray(List<ObjectNode> items) {
        ArrayNode array = objectMapper.createArrayNode();
        items.forEach(array::add);
        return array;
    }

    private ObjectNode itemEvent(int outputIndex, ObjectNode item) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("output_index", outputIndex);
        event.set("item", item);
        return event;
    }

    private ObjectNode withResponse(ObjectNode response) {
        ObjectNode event = objectMapper.createObjectNode();
        event.set("response", response);
        return event;
    }

    private String compactId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String safeChatName(String namespace, String name) {
        String raw = namespace == null ? name : namespace + "__" + name;
        String safe = raw.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (safe.isBlank()) {
            safe = "tool";
        }
        return safe.length() <= CHAT_TOOL_NAME_MAX_LENGTH ? safe
                : safe.substring(0, CHAT_TOOL_NAME_MAX_LENGTH);
    }

    private final class ToolRegistry {
        private final Map<String, ToolDescriptor> byChatName = new LinkedHashMap<>();

        String register(ToolKind kind, String namespace, String name, String execution) {
            if (findChatName(kind, namespace, name) != null) {
                throw new BadRequestException("duplicate tool '" + displayName(namespace, name) + "'");
            }
            String base = safeChatName(namespace, name);
            String candidate = base;
            int suffix = 2;
            while (byChatName.containsKey(candidate)) {
                String ending = "_" + suffix++;
                int prefixLength = Math.min(base.length(), CHAT_TOOL_NAME_MAX_LENGTH - ending.length());
                candidate = base.substring(0, prefixLength) + ending;
            }
            byChatName.put(candidate, new ToolDescriptor(kind, namespace, name, execution));
            return candidate;
        }

        String findChatName(ToolKind kind, String namespace, String name) {
            for (Map.Entry<String, ToolDescriptor> entry : byChatName.entrySet()) {
                ToolDescriptor descriptor = entry.getValue();
                if (descriptor.kind() == kind && equalsNullable(descriptor.namespace(), namespace)
                        && descriptor.name().equals(name)) {
                    return entry.getKey();
                }
            }
            return null;
        }

        private boolean equalsNullable(String left, String right) {
            return left == null ? right == null : left.equals(right);
        }

        private String displayName(String namespace, String name) {
            return namespace == null ? name : namespace + "." + name;
        }
    }
}
