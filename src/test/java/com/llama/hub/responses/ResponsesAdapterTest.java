package com.llama.hub.responses;

import com.llama.hub.service.CallRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponsesAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ResponsesAdapter adapter = new ResponsesAdapter(objectMapper);

    @Test
    void convertsCurrentResponsesRequestIncludingNamespacesCustomToolsAndHistory() throws Exception {
        String request = """
                {
                  "model":"local-model",
                  "instructions":"base instruction",
                  "input":[
                    {"type":"message","role":"developer","content":[{"type":"input_text","text":"developer instruction"}]},
                    {"type":"message","role":"user","content":[{"type":"input_text","text":"do work"}]},
                    {"type":"function_call","call_id":"call_1","namespace":"mcp.demo","name":"lookup","arguments":"{\\\"id\\\":1}"},
                    {"type":"custom_tool_call","call_id":"call_2","namespace":"editor","name":"apply_patch","input":"*** Begin Patch"},
                    {"type":"function_call_output","call_id":"call_1","output":"found"},
                    {"type":"custom_tool_call_output","call_id":"call_2","output":"done"}
                  ],
                  "tools":[
                    {"type":"namespace","name":"mcp.demo","description":"MCP tools","tools":[
                      {"type":"function","name":"lookup","description":"lookup","parameters":{"type":"object"}},
                      {"type":"custom","name":"freeform","description":"free form","format":{"type":"text"}}
                    ]},
                    {"type":"namespace","name":"editor","description":"Editor","tools":[
                      {"type":"custom","name":"apply_patch","description":"patch","format":{"type":"grammar","syntax":"lark","definition":"start: PATCH"}}
                    ]}
                  ],
                  "tool_choice":{"type":"function","namespace":"mcp.demo","name":"lookup"},
                  "text":{"format":{"type":"json_schema","name":"answer","schema":{"type":"object"},"strict":true}},
                  "max_output_tokens":321,
                  "stream":true
                }
                """;

        ResponsesAdapter.PreparedChatRequest prepared = adapter.toChatRequest(bytes(request));
        JsonNode chat = objectMapper.readTree(prepared.body);

        assertEquals("system", chat.path("messages").path(0).path("role").asText());
        assertEquals("base instruction\n\ndeveloper instruction",
                chat.path("messages").path(0).path("content").asText());
        assertEquals("user", chat.path("messages").path(1).path("role").asText());
        assertEquals(2, chat.path("messages").path(2).path("tool_calls").size());
        assertEquals("tool", chat.path("messages").path(3).path("role").asText());
        assertEquals("tool", chat.path("messages").path(4).path("role").asText());
        assertEquals(3, chat.path("tools").size());
        assertTrue(chat.path("tools").path(1).path("function").path("parameters")
                .path("properties").has("input"));
        assertEquals(321, chat.path("max_tokens").asInt());
        assertTrue(chat.path("stream_options").path("include_usage").asBoolean());
        assertEquals("answer", chat.path("response_format").path("json_schema").path("name").asText());
        assertTrue(chat.path("response_format").path("json_schema").path("strict").asBoolean());

        String chosenName = chat.path("tool_choice").path("function").path("name").asText();
        assertEquals("mcp_demo__lookup", chosenName);
        assertEquals(ResponsesAdapter.ToolKind.FUNCTION, prepared.toolsByChatName.get(chosenName).kind());
        assertTrue(prepared.toolsByChatName.keySet().stream().allMatch(name -> name.length() <= 64));
    }

    @Test
    void restoresFunctionAndCustomToolCallsInNonStreamingResponse() throws Exception {
        String request = """
                {
                  "model":"local-model",
                  "input":"work",
                  "tools":[
                    {"type":"namespace","name":"mcp","description":"MCP","tools":[
                      {"type":"function","name":"lookup","parameters":{"type":"object"}}
                    ]},
                    {"type":"custom","name":"apply_patch","description":"patch"}
                  ]
                }
                """;
        ResponsesAdapter.PreparedChatRequest prepared = adapter.toChatRequest(bytes(request));
        String functionName = chatName(prepared.toolsByChatName, ResponsesAdapter.ToolKind.FUNCTION);
        String customName = chatName(prepared.toolsByChatName, ResponsesAdapter.ToolKind.CUSTOM);

        ObjectNode upstream = (ObjectNode) objectMapper.readTree("""
                {
                  "created":123,
                  "model":"actual-model",
                  "choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[]}}],
                  "usage":null
                }
                """);
        ObjectNode functionCall = upstream.withArray("choices").get(0).withObject("message")
                .withArray("tool_calls").addObject();
        functionCall.put("id", "call_1");
        functionCall.putObject("function").put("name", functionName).put("arguments", "{\"id\":1}");
        ObjectNode customCall = upstream.withArray("choices").get(0).withObject("message")
                .withArray("tool_calls").addObject();
        customCall.put("id", "call_2");
        customCall.putObject("function").put("name", customName)
                .put("arguments", "{\"input\":\"*** Begin Patch\"}");

        JsonNode response = adapter.toResponsesResponse(upstream, "requested-model", prepared.toolsByChatName);
        assertEquals("actual-model", response.path("model").asText());
        assertEquals("function_call", response.path("output").path(0).path("type").asText());
        assertEquals("mcp", response.path("output").path(0).path("namespace").asText());
        assertEquals("lookup", response.path("output").path(0).path("name").asText());
        assertEquals("custom_tool_call", response.path("output").path(1).path("type").asText());
        assertEquals("apply_patch", response.path("output").path(1).path("name").asText());
        assertEquals("*** Begin Patch", response.path("output").path(1).path("input").asText());
        assertTrue(response.path("usage").isNull());
    }

    @Test
    void streamingWaitsForCompleteToolNameAndEmitsCompletedFunctionCall() throws Exception {
        ResponsesAdapter.PreparedChatRequest prepared = adapter.toChatRequest(bytes("""
                {"model":"local-model","input":"lookup","tools":[
                  {"type":"namespace","name":"orders","description":"orders","tools":[
                    {"type":"function","name":"lookup_order","parameters":{"type":"object"}}
                  ]}
                ],"stream":true}
                """));
        String chatName = chatName(prepared.toolsByChatName, ResponsesAdapter.ToolKind.FUNCTION);
        int split = chatName.length() / 2;
        ResponsesAdapter.StreamTranslator translator = adapter.new StreamTranslator(
                "local-model", prepared.toolsByChatName);

        String start = translator.start();
        String first = translator.onChunk(toolChunk("call_1", chatName.substring(0, split), null, null));
        String second = translator.onChunk(toolChunk(null, chatName.substring(split), "{\"id\":1}", null));
        translator.onChunk(finishChunk("tool_calls"));
        CallRecorder.Usage usage = new CallRecorder.Usage();
        usage.prompt = 10;
        usage.completion = 5;
        usage.total = 15;
        usage.cached = 2;
        String done = translator.finish(usage);

        assertTrue(first.isEmpty());
        List<JsonNode> events = parseSse(start + second + done);
        JsonNode added = event(events, "response.output_item.added", "function_call");
        assertNotNull(added);
        assertEquals("orders", added.path("item").path("namespace").asText());
        assertEquals("lookup_order", added.path("item").path("name").asText());
        JsonNode itemDone = event(events, "response.output_item.done", "function_call");
        assertEquals("{\"id\":1}", itemDone.path("item").path("arguments").asText());
        JsonNode completed = event(events, "response.completed", null);
        assertEquals(15, completed.path("response").path("usage").path("total_tokens").asInt());
        assertEquals("local-model", completed.path("response").path("model").asText());

        for (int index = 0; index < events.size(); index++) {
            assertEquals(index, events.get(index).path("sequence_number").asInt());
        }
    }

    @Test
    void streamingCustomToolAndLengthFinishUseCorrectResponseTypes() throws Exception {
        ResponsesAdapter.PreparedChatRequest prepared = adapter.toChatRequest(bytes("""
                {"model":"local-model","input":"patch","tools":[
                  {"type":"custom","name":"apply_patch","description":"patch"}
                ],"stream":true}
                """));
        String chatName = chatName(prepared.toolsByChatName, ResponsesAdapter.ToolKind.CUSTOM);
        ResponsesAdapter.StreamTranslator translator = adapter.new StreamTranslator(
                "local-model", prepared.toolsByChatName);

        String eventsText = translator.start()
                + translator.onChunk(toolChunk("call_2", chatName,
                "{\"input\":\"*** Begin Patch\"}", null))
                + translator.onChunk(finishChunk("length"))
                + translator.finish(null);
        List<JsonNode> events = parseSse(eventsText);

        JsonNode added = event(events, "response.output_item.added", "custom_tool_call");
        assertNotNull(added);
        JsonNode itemDone = event(events, "response.output_item.done", "custom_tool_call");
        assertEquals("*** Begin Patch", itemDone.path("item").path("input").asText());
        assertNotNull(event(events, "response.custom_tool_call_input.delta", null));
        assertNotNull(event(events, "response.incomplete", null));
        assertEquals("max_output_tokens", event(events, "response.incomplete", null)
                .path("response").path("incomplete_details").path("reason").asText());
        assertFalse(events.stream().anyMatch(node -> "response.completed".equals(node.path("type").asText())));
    }

    @Test
    void ignoresServerExecutedBuiltInToolsAndTheirHistoryButRejectsUnknownTypes() throws Exception {
        ResponsesAdapter.PreparedChatRequest prepared = adapter.toChatRequest(bytes("""
                {"model":"local-model","input":"search","tools":[
                  {"type":"web_search"},
                  {"type":"web_search_preview"},
                  {"type":"function","name":"local","parameters":{"type":"object"}}
                ]}
                """));
        JsonNode chat = objectMapper.readTree(prepared.body);
        assertEquals(1, chat.path("tools").size());
        assertEquals("local", chat.path("tools").path(0).path("function").path("name").asText());

        ResponsesAdapter.PreparedChatRequest withHistory = adapter.toChatRequest(bytes("""
                {"model":"local-model","input":[
                  {"type":"message","role":"user","content":"hi"},
                  {"type":"web_search_call","id":"ws_1","status":"completed","action":{"type":"search","query":"x"}},
                  {"type":"message","role":"assistant","content":"done"}
                ]}
                """));
        JsonNode historyChat = objectMapper.readTree(withHistory.body);
        assertEquals(2, historyChat.path("messages").size());

        ResponsesAdapter.BadRequestException error = assertThrows(ResponsesAdapter.BadRequestException.class,
                () -> adapter.toChatRequest(bytes("""
                        {"model":"local-model","input":"x","tools":[{"type":"mystery_tool"}]}
                        """)));
        assertTrue(error.getMessage().contains("mystery_tool"));
    }

    @Test
    void rejectsUpstreamErrorEvents() {
        ResponsesAdapter.StreamTranslator translator = adapter.new StreamTranslator("model", Map.of());
        ResponsesAdapter.UpstreamStreamException error = assertThrows(
                ResponsesAdapter.UpstreamStreamException.class,
                () -> translator.onChunk("{\"error\":{\"type\":\"server_error\",\"message\":\"boom\"}}"));
        assertEquals("server_error: boom", error.getMessage());
    }

    @Test
    void upstreamDoneLineIsTheOnlyNormalStreamingTerminalSignal() {
        CallRecorder recorder = new CallRecorder(objectMapper, null, null);
        ResponsesProxyService proxy = new ResponsesProxyService(
                WebClient.builder(), objectMapper, recorder, adapter);
        ResponsesAdapter.StreamTranslator translator = adapter.new StreamTranslator("model", Map.of());
        StringBuilder events = new StringBuilder(translator.start());
        AtomicReference<String> usageSource = new AtomicReference<>(
                "data: {\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":5,\"total_tokens\":9}}");
        AtomicBoolean upstreamDone = new AtomicBoolean(false);

        proxy.collectStreamLine("data: [DONE]", translator, events, usageSource, upstreamDone);

        assertTrue(upstreamDone.get());
        assertTrue(translator.isTerminal());
        assertTrue(events.toString().contains("event: response.completed"));
        assertTrue(events.toString().contains("\"total_tokens\":9"));
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private String chatName(Map<String, ResponsesAdapter.ToolDescriptor> tools,
                            ResponsesAdapter.ToolKind kind) {
        return tools.entrySet().stream()
                .filter(entry -> entry.getValue().kind() == kind)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
    }

    private String toolChunk(String callId, String name, String arguments, String finishReason) {
        ObjectNode chunk = objectMapper.createObjectNode();
        chunk.put("model", "local-model");
        ObjectNode choice = chunk.putArray("choices").addObject();
        ObjectNode delta = choice.putObject("delta");
        ObjectNode call = delta.putArray("tool_calls").addObject();
        call.put("index", 0);
        if (callId != null) {
            call.put("id", callId);
        }
        ObjectNode function = call.putObject("function");
        if (name != null) {
            function.put("name", name);
        }
        if (arguments != null) {
            function.put("arguments", arguments);
        }
        if (finishReason == null) {
            choice.putNull("finish_reason");
        } else {
            choice.put("finish_reason", finishReason);
        }
        return chunk.toString();
    }

    private String finishChunk(String finishReason) {
        return "{\"choices\":[{\"delta\":{},\"finish_reason\":\"" + finishReason + "\"}]}";
    }

    private List<JsonNode> parseSse(String value) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (String line : value.split("\\R")) {
            if (line.startsWith("data: ")) {
                events.add(objectMapper.readTree(line.substring(6)));
            }
        }
        return events;
    }

    private JsonNode event(List<JsonNode> events, String type, String itemType) {
        return events.stream()
                .filter(node -> type.equals(node.path("type").asText()))
                .filter(node -> itemType == null || itemType.equals(node.path("item").path("type").asText()))
                .findFirst()
                .orElse(null);
    }
}
