package io.ohmvir.plugins.anthropicclient;

import static io.ohmvir.plugins.anthropicclient.FakeAnthropicApi.message;
import static io.ohmvir.plugins.anthropicclient.FakeAnthropicApi.modelInfo;
import static io.ohmvir.plugins.anthropicclient.FakeAnthropicApi.redactedThinking;
import static io.ohmvir.plugins.anthropicclient.FakeAnthropicApi.text;
import static io.ohmvir.plugins.anthropicclient.FakeAnthropicApi.thinking;
import static io.ohmvir.plugins.anthropicclient.FakeAnthropicApi.toolUse;
import static org.junit.jupiter.api.Assertions.*;

import com.anthropic.models.messages.StopReason;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import hudson.model.Descriptor;
import io.ohmvir.plugins.jenkinsaisynapse.api.ModelContent;
import io.ohmvir.plugins.jenkinsaisynapse.api.ModelConversation;
import io.ohmvir.plugins.jenkinsaisynapse.api.input.*;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelData;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelFinishReason;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelThinkingLevel;
import io.ohmvir.plugins.jenkinsaisynapse.api.output.*;
import io.ohmvir.plugins.jenkinsaisynapse.api.skills.SkillData;
import io.ohmvir.plugins.jenkinsaisynapse.api.tools.Tool;
import io.ohmvir.plugins.jenkinsaisynapse.api.tools.ToolArgumentDescription;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises request building and response handling against a fake Anthropic API. */
class AnthropicClientRequestTest {
    private static final String MODEL = "claude-test";

    private FakeAnthropicApi api;
    private AnthropicClient client;
    private AnthropicModelSettings settings;
    private ModelData modelData;

    @BeforeEach
    void setUp() throws Exception {
        api = new FakeAnthropicApi();
        client = new TestAnthropicClient();
        settings = new AnthropicModelSettings(MODEL, "cred-id", api.baseUrl());
        modelData = new ModelData(settings);
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    // ---- Basic request/response ----

    @Test
    void sendsBasicRequestAndMapsResponse() throws Exception {
        api.enqueueMessage(message("end_turn", 12, 7, 3, 2, text("Hello there")));

        ModelRequest request = new ModelRequest();
        request.addInput(new SystemPromptContent("Be concise."));
        request.addInput(new InputSkillContent(skill("deploy", "Deploys \"things\" & <stuff>", "Run the deploy.")));
        request.addInput(new InputTextContent("Hi"));
        request.addInput(new StopSequencesContent(List.of("STOP")));

        List<ModelOutput> outputs = takeStep(request);

        assertEquals(1, api.messageRequests().size());
        FakeAnthropicApi.RecordedRequest recorded = api.messageRequests().get(0);
        assertEquals("test-api-key", recorded.headers().get("x-api-key"));
        assertEquals("2023-06-01", recorded.headers().get("anthropic-version"));

        JsonObject body = recorded.json();
        assertEquals(MODEL, body.get("model").getAsString());
        assertEquals(
                AnthropicClientSettings.DEFAULT_MAX_TOKENS,
                body.get("max_tokens").getAsLong());
        assertEquals("STOP", body.getAsJsonArray("stop_sequences").get(0).getAsString());
        assertFalse(body.has("thinking"));
        assertFalse(body.has("temperature"));

        String system = body.get("system").getAsString();
        assertTrue(system.startsWith("Be concise.\n\n"));
        assertTrue(
                system.contains(
                        "<skill name=\"deploy\" description=\"Deploys &quot;things&quot; &amp; &lt;stuff&gt;\">"),
                system);
        assertTrue(system.contains("Run the deploy."));

        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals(1, messages.size());
        assertEquals("user", role(messages, 0));
        assertEquals(
                "Hi", content(messages, 0).get(0).getAsJsonObject().get("text").getAsString());

        assertEquals("Hello there", single(outputs, OutputTextContent.class).getText());
        assertEquals(
                ModelFinishReason.STOP,
                single(outputs, FinishReasonContent.class).getReason());
        TokenUtilizationContent usage = single(outputs, TokenUtilizationContent.class);
        // input_tokens excludes cache reads and writes, so they are added back for the total
        assertEquals(17, usage.getInputTokensUsed());
        assertEquals(7, usage.getOutputTokensUsed());
        assertEquals(3, usage.getCachedTokensUsed());

        // ModelRequest.execute keeps stepping until an empty result; no new inputs means no new API call
        assertTrue(takeStep(request).isEmpty());
        assertEquals(1, api.messageRequests().size());
    }

    @Test
    void usesConfiguredClientSettings() throws Exception {
        api.enqueueMessage(message("end_turn", text("ok")));
        ModelRequest request = textRequest("Hi");

        client.takeStep(
                modelData, settings, new AnthropicClientSettings(30L, "2023-06-01", 1234L), request, inputs(request));

        assertEquals(1234L, lastMessageRequest().get("max_tokens").getAsLong());
    }

    @Test
    void explicitMaxTokensAreClampedToTheModelLimit() throws Exception {
        modelData.setMaxOutputTokens(8000L);
        api.enqueueMessage(message("end_turn", text("ok")));
        ModelRequest request = textRequest("Hi");
        request.addInput(new MaxOutputTokensContent(200_000L));

        takeStep(request);

        assertEquals(8000L, lastMessageRequest().get("max_tokens").getAsLong());
    }

    @Test
    void emptyTextInputsAreNotSent() throws Exception {
        api.enqueueMessage(message("end_turn", text("ok")));
        ModelRequest request = new ModelRequest();
        request.addInput(new InputTextContent(""));
        request.addInput(new InputTextContent("Real question"));

        takeStep(request);

        JsonArray blocks = content(lastMessageRequest().getAsJsonArray("messages"), 0);
        assertEquals(1, blocks.size());
        assertEquals(
                "Real question", blocks.get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void requestWithoutMessageContentFailsWithoutCallingTheApi() {
        ModelRequest request = new ModelRequest();
        request.addInput(new SystemPromptContent("Only a system prompt"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> takeStep(request));
        assertTrue(e.getMessage().contains("no message content"));
        assertTrue(api.messageRequests().isEmpty());
    }

    // ---- Tool use ----

    @Test
    void toolArgumentsAreParsedAsJson() throws Exception {
        api.enqueueMessage(message(
                "tool_use",
                toolUse(
                        "toolu_1",
                        "search",
                        "{\"query\":\"hello world, a=b {x}\",\"limit\":3,\"filters\":{\"tags\":[\"a\",\"b\"]}}")));

        List<ModelOutput> outputs = takeStep(toolRequest("Find things"));

        ToolCallContent call = single(outputs, ToolCallContent.class);
        assertEquals("toolu_1", call.getToolUseId());
        assertEquals("search", call.getName());
        assertEquals(
                "hello world, a=b {x}", call.getToolArguments().get("query").getAsString());
        assertEquals(3, call.getToolArguments().get("limit").getAsInt());
        assertEquals(
                "b",
                call.getToolArguments()
                        .getAsJsonObject("filters")
                        .getAsJsonArray("tags")
                        .get(1)
                        .getAsString());
        assertEquals(
                ModelFinishReason.TOOL_CALLS,
                single(outputs, FinishReasonContent.class).getReason());
    }

    @Test
    void toolLoopReplaysMessagesInApiOrder() throws Exception {
        api.enqueueMessage(
                message("tool_use", text("Let me search."), toolUse("toolu_1", "search", "{\"query\":\"first\"}")));
        api.enqueueMessage(message("tool_use", toolUse("toolu_2", "search", "{\"query\":\"second\"}")));
        api.enqueueMessage(message("end_turn", text("Done.")));

        ModelRequest request = toolRequest("Find things");
        List<ModelOutput> outputs = execute(
                request,
                call -> "result for " + call.getToolArguments().get("query").getAsString());

        assertEquals(3, api.messageRequests().size());
        assertEquals(
                "Done.",
                outputs.stream()
                        .filter(OutputTextContent.class::isInstance)
                        .map(o -> ((OutputTextContent) o).getText())
                        .reduce((a, b) -> b)
                        .orElseThrow());

        // Second request: the prompt, the tool use, then the tool result
        JsonArray second = api.messageRequests().get(1).json().getAsJsonArray("messages");
        assertEquals(List.of("user", "assistant", "user"), roles(second));
        assertEquals(
                "Find things",
                content(second, 0).get(0).getAsJsonObject().get("text").getAsString());
        JsonArray firstAssistant = content(second, 1);
        assertEquals("text", type(firstAssistant, 0));
        assertEquals("tool_use", type(firstAssistant, 1));
        assertEquals(
                "first",
                firstAssistant
                        .get(1)
                        .getAsJsonObject()
                        .getAsJsonObject("input")
                        .get("query")
                        .getAsString());
        JsonObject firstResult = content(second, 2).get(0).getAsJsonObject();
        assertEquals("tool_result", firstResult.get("type").getAsString());
        assertEquals("toolu_1", firstResult.get("tool_use_id").getAsString());
        assertEquals("result for first", firstResult.get("content").getAsString());

        // Third request: each tool result directly follows the tool use it answers
        JsonArray third = api.messageRequests().get(2).json().getAsJsonArray("messages");
        assertEquals(List.of("user", "assistant", "user", "assistant", "user"), roles(third));
        assertEquals(
                "toolu_2", content(third, 3).get(0).getAsJsonObject().get("id").getAsString());
        JsonArray secondResultMessage = content(third, 4);
        assertEquals(1, secondResultMessage.size());
        assertEquals(
                "toolu_2",
                secondResultMessage.get(0).getAsJsonObject().get("tool_use_id").getAsString());

        // Tools are sent on every step
        for (FakeAnthropicApi.RecordedRequest recorded : api.messageRequests()) {
            assertEquals(
                    "search",
                    recorded.json()
                            .getAsJsonArray("tools")
                            .get(0)
                            .getAsJsonObject()
                            .get("name")
                            .getAsString());
        }
    }

    @Test
    void toolResultsComeBeforeOtherContentInTheSameMessage() throws Exception {
        api.enqueueMessage(message("tool_use", toolUse("toolu_1", "search", "{\"query\":\"q\"}")));
        api.enqueueMessage(message("end_turn", text("ok")));

        ModelRequest request = toolRequest("Find things");
        takeStep(request);
        // A caller adds extra text alongside the tool result
        request.addInput(new InputTextContent("Also consider this."));
        request.addInput(new ToolCallResponseContent("toolu_1", true, "found", null));
        takeStep(request);

        JsonArray resultMessage = content(lastMessageRequest().getAsJsonArray("messages"), 2);
        assertEquals("tool_result", type(resultMessage, 0));
        assertEquals("text", type(resultMessage, 1));
    }

    @Test
    void failedToolCallIsSentAsError() throws Exception {
        api.enqueueMessage(message("tool_use", toolUse("toolu_1", "search", "{\"query\":\"q\"}")));
        api.enqueueMessage(message("end_turn", text("Sorry.")));

        ModelRequest request = toolRequest("Find things");
        takeStep(request);
        request.addInput(new ToolCallResponseContent("toolu_1", false, null, null));
        takeStep(request);

        JsonObject result = content(lastMessageRequest().getAsJsonArray("messages"), 2)
                .get(0)
                .getAsJsonObject();
        assertTrue(result.get("is_error").getAsBoolean());
        assertFalse(result.get("content").getAsString().isEmpty());
    }

    @Test
    void toolDefinitionsMatchTheApiSchema() throws Exception {
        api.enqueueMessage(message("end_turn", text("ok")));

        takeStep(toolRequest("Find things"));

        JsonObject tool = lastMessageRequest().getAsJsonArray("tools").get(0).getAsJsonObject();
        assertEquals("search", tool.get("name").getAsString());
        assertEquals("Searches the index", tool.get("description").getAsString());
        JsonObject schema = tool.getAsJsonObject("input_schema");
        assertEquals("object", schema.get("type").getAsString());
        JsonObject properties = schema.getAsJsonObject("properties");
        assertEquals("string", properties.getAsJsonObject("query").get("type").getAsString());
        assertEquals(
                "What to search for",
                properties.getAsJsonObject("query").get("description").getAsString());
        assertEquals("integer", properties.getAsJsonObject("limit").get("type").getAsString());
        // A null description must be omitted, not sent as null
        assertFalse(properties.getAsJsonObject("limit").has("description"));
        assertEquals(1, schema.getAsJsonArray("required").size());
        assertEquals("query", schema.getAsJsonArray("required").get(0).getAsString());
    }

    @Test
    void jsonSchemaTypesCoverCommonJavaTypes() {
        assertEquals("string", AnthropicClient.toJsonSchemaType(String.class));
        assertEquals("integer", AnthropicClient.toJsonSchemaType(int.class));
        assertEquals("number", AnthropicClient.toJsonSchemaType(double.class));
        assertEquals("boolean", AnthropicClient.toJsonSchemaType(boolean.class));
        assertEquals("array", AnthropicClient.toJsonSchemaType(ArrayList.class));
        assertEquals("array", AnthropicClient.toJsonSchemaType(String[].class));
        assertEquals("object", AnthropicClient.toJsonSchemaType(java.util.HashMap.class));
        assertEquals("number", AnthropicClient.toJsonSchemaType(java.util.concurrent.atomic.AtomicLong.class));
    }

    // ---- Thinking ----

    @Test
    void thinkingBlocksAreReplayedWithSignatures() throws Exception {
        api.setModelInfo(MODEL, modelInfo(MODEL, 200_000L, 64_000L, false, true, true, false));
        api.enqueueMessage(message(
                "tool_use",
                thinking("I should search.", "sig-1"),
                redactedThinking("opaque-data"),
                toolUse("toolu_1", "search", "{\"query\":\"q\"}")));
        api.enqueueMessage(message("end_turn", text("ok")));

        ModelRequest request = toolRequest("Find things");
        request.addInput(new ThinkingLevelContent(ModelThinkingLevel.LOW));
        List<ModelOutput> outputs = takeStep(request);
        assertEquals("I should search.", single(outputs, ThinkingContent.class).getThinking());

        request.addInput(new ToolCallResponseContent("toolu_1", true, "found", null));
        takeStep(request);

        JsonArray assistant = content(lastMessageRequest().getAsJsonArray("messages"), 1);
        assertEquals("thinking", type(assistant, 0));
        assertEquals(
                "sig-1", assistant.get(0).getAsJsonObject().get("signature").getAsString());
        assertEquals("redacted_thinking", type(assistant, 1));
        assertEquals(
                "opaque-data", assistant.get(1).getAsJsonObject().get("data").getAsString());
        assertEquals("tool_use", type(assistant, 2));
    }

    @Test
    void emptyThinkingTextIsNotReturnedAsOutput() throws Exception {
        api.enqueueMessage(message("end_turn", thinking("", "sig"), text("ok")));

        List<ModelOutput> outputs = takeStep(textRequest("Hi"));

        assertTrue(outputs.stream().noneMatch(ThinkingContent.class::isInstance));
    }

    @Test
    void adaptiveThinkingModelsUseEffortInsteadOfBudget() throws Exception {
        api.setModelInfo(MODEL, modelInfo(MODEL, 1_000_000L, 128_000L, true, false, true, true));
        api.enqueueMessage(message("end_turn", text("ok")));

        ModelRequest request = textRequest("Hi");
        request.addInput(new ThinkingLevelContent(ModelThinkingLevel.EXTRA_HIGH));
        request.addInput(new TemperatureContent(0.2));
        takeStep(request);

        JsonObject body = lastMessageRequest();
        JsonObject thinking = body.getAsJsonObject("thinking");
        assertEquals("adaptive", thinking.get("type").getAsString());
        assertFalse(thinking.has("budget_tokens"));
        assertEquals("summarized", thinking.get("display").getAsString());
        assertEquals(
                "xhigh", body.getAsJsonObject("output_config").get("effort").getAsString());
        assertEquals(
                AnthropicClientSettings.DEFAULT_MAX_TOKENS,
                body.get("max_tokens").getAsLong());
        assertFalse(body.has("temperature"));
    }

    @Test
    void adaptiveThinkingSkipsEffortLevelsTheModelDoesNotSupport() throws Exception {
        api.setModelInfo(MODEL, modelInfo(MODEL, 1_000_000L, 128_000L, true, false, true, false));
        api.enqueueMessage(message("end_turn", text("ok")));
        api.enqueueMessage(message("end_turn", text("ok")));

        ModelRequest xhigh = textRequest("Hi");
        xhigh.addInput(new ThinkingLevelContent(ModelThinkingLevel.EXTRA_HIGH));
        takeStep(xhigh);
        assertFalse(lastMessageRequest().has("output_config"));

        ModelRequest on = textRequest("Hi");
        on.addInput(new ThinkingLevelContent(ModelThinkingLevel.ON));
        takeStep(on);
        assertEquals(
                "adaptive",
                lastMessageRequest().getAsJsonObject("thinking").get("type").getAsString());
        assertFalse(lastMessageRequest().has("output_config"));
    }

    @Test
    void budgetThinkingGrowsMaxTokensToFitTheBudget() throws Exception {
        api.setModelInfo(MODEL, modelInfo(MODEL, 200_000L, 64_000L, false, true, true, false));
        JsonObject body = sendWithThinking(ModelThinkingLevel.HIGH, null);

        assertEquals("enabled", body.getAsJsonObject("thinking").get("type").getAsString());
        assertEquals(
                16384L, body.getAsJsonObject("thinking").get("budget_tokens").getAsLong());
        assertEquals(16384L + 2048L, body.get("max_tokens").getAsLong());
    }

    @Test
    void budgetThinkingRespectsAnExplicitMaxTokens() throws Exception {
        api.setModelInfo(MODEL, modelInfo(MODEL, 200_000L, 64_000L, false, true, true, false));
        JsonObject body = sendWithThinking(ModelThinkingLevel.HIGH, 4000L);

        assertEquals(4000L, body.get("max_tokens").getAsLong());
        assertEquals(
                3999L, body.getAsJsonObject("thinking").get("budget_tokens").getAsLong());
    }

    @Test
    void budgetThinkingStaysWithinTheModelOutputLimit() throws Exception {
        api.setModelInfo(MODEL, modelInfo(MODEL, 200_000L, 8192L, false, true, true, false));
        modelData.setMaxOutputTokens(8192L);
        JsonObject body = sendWithThinking(ModelThinkingLevel.MAX, null);

        assertEquals(8192L, body.get("max_tokens").getAsLong());
        assertEquals(
                8191L, body.getAsJsonObject("thinking").get("budget_tokens").getAsLong());
    }

    @Test
    void budgetThinkingNeverGoesBelowTheApiMinimum() throws Exception {
        api.setModelInfo(MODEL, modelInfo(MODEL, 200_000L, 64_000L, false, true, true, false));
        JsonObject body = sendWithThinking(ModelThinkingLevel.LOW, 500L);

        long budget = body.getAsJsonObject("thinking").get("budget_tokens").getAsLong();
        assertEquals(AnthropicClient.MIN_THINKING_BUDGET_TOKENS, budget);
        assertTrue(body.get("max_tokens").getAsLong() > budget);
    }

    @Test
    void thinkingOffSendsNoThinkingConfiguration() throws Exception {
        api.enqueueMessage(message("end_turn", text("ok")));
        ModelRequest request = textRequest("Hi");
        request.addInput(new ThinkingLevelContent(ModelThinkingLevel.OFF));

        takeStep(request);

        assertFalse(lastMessageRequest().has("thinking"));
        // No thinking or sampling requested, so no Models API lookup is needed
        assertTrue(api.modelRequests().isEmpty());
    }

    @Test
    void modelFeaturesAreLookedUpOncePerModel() throws Exception {
        api.setModelInfo(MODEL, modelInfo(MODEL, 1_000_000L, 128_000L, true, false, true, true));
        sendWithThinking(ModelThinkingLevel.HIGH, null);
        sendWithThinking(ModelThinkingLevel.LOW, null);

        assertEquals(1, api.modelRequests().size());
    }

    @Test
    void thinkingFallsBackToBudgetWhenTheModelsApiIsUnavailable() throws Exception {
        // No model info registered: the Models API returns 404
        JsonObject body = sendWithThinking(ModelThinkingLevel.MEDIUM, null);

        assertEquals("enabled", body.getAsJsonObject("thinking").get("type").getAsString());
        assertEquals(
                8192L, body.getAsJsonObject("thinking").get("budget_tokens").getAsLong());
    }

    // ---- Sampling ----

    @Test
    void samplingParametersAreSentToModelsThatAcceptThem() throws Exception {
        api.setModelInfo(MODEL, modelInfo(MODEL, 200_000L, 64_000L, false, true, true, false));
        api.enqueueMessage(message("end_turn", text("ok")));
        ModelRequest request = textRequest("Hi");
        request.addInput(new TemperatureContent(1.7));
        request.addInput(new TopPContent(0.9));
        request.addInput(new TopKContent(4.6));

        takeStep(request);

        JsonObject body = lastMessageRequest();
        assertEquals(1.0, body.get("temperature").getAsDouble());
        // temperature and top_p can't both be set on current models
        assertFalse(body.has("top_p"));
        assertEquals(5L, body.get("top_k").getAsLong());
    }

    @Test
    void topPIsSentWhenTemperatureIsNot() throws Exception {
        api.setModelInfo(MODEL, modelInfo(MODEL, 200_000L, 64_000L, false, true, true, false));
        api.enqueueMessage(message("end_turn", text("ok")));
        ModelRequest request = textRequest("Hi");
        request.addInput(new TopPContent(0.9));

        takeStep(request);

        assertEquals(0.9, lastMessageRequest().get("top_p").getAsDouble(), 1e-9);
    }

    @Test
    void samplingParametersAreDroppedForModelsThatRejectThem() throws Exception {
        api.setModelInfo(MODEL, modelInfo(MODEL, 1_000_000L, 128_000L, true, false, true, true));
        api.enqueueMessage(message("end_turn", text("ok")));
        ModelRequest request = textRequest("Hi");
        request.addInput(new TemperatureContent(0.5));
        request.addInput(new TopKContent(10));

        takeStep(request);

        JsonObject body = lastMessageRequest();
        assertFalse(body.has("temperature"));
        assertFalse(body.has("top_k"));
    }

    // ---- Error handling ----

    @Test
    void requestCanBeRetriedAfterAnApiError() throws Exception {
        api.enqueueError(400, "invalid_request_error", "bad request");
        api.enqueueMessage(message("end_turn", text("ok")));
        ModelRequest request = textRequest("Hi");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> takeStep(request));
        assertTrue(e.getMessage().contains("HTTP 400"), e.getMessage());

        List<ModelOutput> outputs = takeStep(request);
        assertEquals("ok", single(outputs, OutputTextContent.class).getText());
        assertEquals(2, api.messageRequests().size());
    }

    @Test
    void responseWithoutContentDoesNotLoopForever() throws Exception {
        api.enqueueMessage(message("refusal"));
        ModelRequest request = textRequest("Hi");

        List<ModelOutput> outputs = takeStep(request);
        assertEquals(
                ModelFinishReason.SAFEGUARD,
                single(outputs, FinishReasonContent.class).getReason());
        assertTrue(takeStep(request).isEmpty());
        assertEquals(1, api.messageRequests().size());
    }

    @Test
    void stopReasonsMapToFinishReasons() {
        assertEquals(ModelFinishReason.STOP, AnthropicClient.toFinishReason(StopReason.END_TURN));
        assertEquals(ModelFinishReason.STOP, AnthropicClient.toFinishReason(StopReason.STOP_SEQUENCE));
        assertEquals(ModelFinishReason.TOKEN_CAP, AnthropicClient.toFinishReason(StopReason.MAX_TOKENS));
        assertEquals(
                ModelFinishReason.TOKEN_CAP, AnthropicClient.toFinishReason(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED));
        assertEquals(ModelFinishReason.TOOL_CALLS, AnthropicClient.toFinishReason(StopReason.TOOL_USE));
        assertEquals(ModelFinishReason.SAFEGUARD, AnthropicClient.toFinishReason(StopReason.REFUSAL));
    }

    // ---- Conversations ----

    @Test
    void conversationHistoryIsReplayedBeforeTheNewTurn() throws Exception {
        JsonObject args = JsonParser.parseString("{\"query\":\"old search\"}").getAsJsonObject();
        ModelConversation conversation = conversation(
                new InputTextContent("Earlier question"),
                new ToolCallContent("toolu_old", args, "search"),
                new ToolCallResponseContent("toolu_old", true, "old result", null),
                new OutputTextContent("Earlier answer"),
                new ThinkingContent("not replayable"),
                new FinishReasonContent(ModelFinishReason.STOP));
        api.enqueueMessage(message("end_turn", text("New answer")));

        ModelRequest request = new ModelRequest();
        request.addInput(new InputConversationContent(conversation));
        request.addInput(new InputTextContent("Follow-up"));
        takeStep(request);

        JsonArray messages = lastMessageRequest().getAsJsonArray("messages");
        assertEquals(List.of("user", "assistant", "user", "assistant", "user"), roles(messages));
        JsonObject toolUse = content(messages, 1).get(0).getAsJsonObject();
        assertEquals("tool_use", toolUse.get("type").getAsString());
        // The tool input must be sent as a JSON object, not a serialized Gson object
        assertEquals("old search", toolUse.getAsJsonObject("input").get("query").getAsString());
        assertEquals("tool_result", type(content(messages, 2), 0));
        assertEquals(
                "Earlier answer",
                content(messages, 3).get(0).getAsJsonObject().get("text").getAsString());
        assertEquals(
                "Follow-up",
                content(messages, 4).get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void reusedRequestContinuesTheConversation() throws Exception {
        api.enqueueMessage(message("end_turn", text("First answer")));
        api.enqueueMessage(message("end_turn", text("Second answer")));

        ModelRequest request = textRequest("First question");
        takeStep(request);
        assertTrue(takeStep(request).isEmpty());
        request.addInput(new InputTextContent("Second question"));
        takeStep(request);

        JsonArray messages = lastMessageRequest().getAsJsonArray("messages");
        assertEquals(List.of("user", "assistant", "user"), roles(messages));
        assertEquals(
                "Second question",
                content(messages, 2).get(0).getAsJsonObject().get("text").getAsString());
    }

    // ---- Files and images ----

    @Test
    void filesAreSentAsTheMatchingContentBlocks() throws Exception {
        api.enqueueMessage(message("end_turn", text("ok")));
        byte[] pdf = "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8);
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G'};

        ModelRequest request = new ModelRequest();
        request.addInput(new InputFileContent("report.pdf", pdf, "application/pdf"));
        request.addInput(new InputFileContent(
                "notes.txt", "some notes".getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8"));
        request.addInput(new InputFileContent("diagram.png", png, "image/png"));
        request.addInput(new InputFileContent("empty.txt", new byte[0], "text/plain"));
        request.addInput(new InputTextContent("Summarize"));
        takeStep(request);

        JsonArray blocks = content(lastMessageRequest().getAsJsonArray("messages"), 0);
        assertEquals(4, blocks.size());

        JsonObject pdfBlock = blocks.get(0).getAsJsonObject();
        assertEquals("document", pdfBlock.get("type").getAsString());
        assertEquals("base64", pdfBlock.getAsJsonObject("source").get("type").getAsString());
        assertEquals(
                "application/pdf",
                pdfBlock.getAsJsonObject("source").get("media_type").getAsString());
        assertEquals(
                Base64.getEncoder().encodeToString(pdf),
                pdfBlock.getAsJsonObject("source").get("data").getAsString());
        assertEquals("report.pdf", pdfBlock.get("title").getAsString());
        assertTrue(pdfBlock.getAsJsonObject("citations").get("enabled").getAsBoolean());

        JsonObject textBlock = blocks.get(1).getAsJsonObject();
        assertEquals("document", textBlock.get("type").getAsString());
        assertEquals("text", textBlock.getAsJsonObject("source").get("type").getAsString());
        assertEquals(
                "text/plain",
                textBlock.getAsJsonObject("source").get("media_type").getAsString());
        assertEquals(
                "some notes", textBlock.getAsJsonObject("source").get("data").getAsString());

        JsonObject imageBlock = blocks.get(2).getAsJsonObject();
        assertEquals("image", imageBlock.get("type").getAsString());
        assertEquals(
                "image/png",
                imageBlock.getAsJsonObject("source").get("media_type").getAsString());

        assertEquals("Summarize", blocks.get(3).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void imagesAreSentAsBase64WithTheirMediaType() throws Exception {
        api.enqueueMessage(message("end_turn", text("ok")));
        ModelRequest request = new ModelRequest();
        request.addInput(new InputImageContent(
                new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB)));
        request.addInput(new InputTextContent("Describe"));
        takeStep(request);

        JsonObject image = content(lastMessageRequest().getAsJsonArray("messages"), 0)
                .get(0)
                .getAsJsonObject();
        assertEquals("image", image.get("type").getAsString());
        JsonObject source = image.getAsJsonObject("source");
        assertEquals("base64", source.get("type").getAsString());
        assertEquals("image/png", source.get("media_type").getAsString());
        assertFalse(source.get("data").getAsString().startsWith("data:"));
    }

    @Test
    void citationsAreMappedToCitationOutputs() throws Exception {
        String citedText =
                "{\"type\":\"text\",\"text\":\"The sky is blue.\",\"citations\":[{\"type\":\"char_location\","
                        + "\"cited_text\":\"sky is blue\",\"document_index\":1,\"document_title\":\"notes.txt\","
                        + "\"start_char_index\":4,\"end_char_index\":15,\"file_id\":null}]}";
        api.enqueueMessage(message("end_turn", citedText));

        List<ModelOutput> outputs = takeStep(textRequest("Hi"));

        CitationContent citation = single(outputs, CitationContent.class);
        assertEquals("sky is blue", citation.getCitedText());
        assertEquals(1, citation.getDocumentIndex());
        assertEquals("notes.txt", citation.getDocumentTitle());
        assertEquals(4, citation.getStartCharIndex());
        assertEquals(15, citation.getEndCharIndex());
    }

    // ---- Output filtering ----

    @Test
    void outputsAreFilteredToTheRequestedTypesButToolCallsAreKept() throws Exception {
        api.enqueueMessage(message("tool_use", text("Searching"), toolUse("toolu_1", "search", "{\"query\":\"q\"}")));
        ModelRequest request = toolRequest("Find things");
        request.requestOutputType(OutputTextContent.class);

        List<ModelOutput> outputs = takeStep(request);

        assertEquals(2, outputs.size());
        assertInstanceOf(OutputTextContent.class, outputs.get(0));
        assertInstanceOf(ToolCallContent.class, outputs.get(1));
    }

    // ---- Helpers ----

    /** Avoids the Jenkins credentials lookup. */
    static class TestAnthropicClient extends AnthropicClient {
        @Override
        String resolveApiKey(AnthropicModelSettings configuration) {
            return "test-api-key";
        }
    }

    public static class SearchTool extends Tool {
        public SearchTool() throws NoSuchMethodException {
            super();
        }

        @Override
        public String getName() {
            return "search";
        }

        @Override
        public String getDescription() {
            return "Searches the index";
        }

        @Override
        public List<ToolArgumentDescription> getArguments() {
            return List.of(
                    new ToolArgumentDescription("query", String.class, "What to search for", true),
                    new ToolArgumentDescription("limit", int.class, null, false));
        }

        public String search(String query, int limit) {
            return "results for " + query;
        }
    }

    /** A conversation that doesn't persist to the Jenkins home directory. */
    static class InMemoryConversation extends ModelConversation {
        @Override
        public void save() {}
    }

    private static ModelConversation conversation(ModelContent... contents) throws Exception {
        ModelConversation conversation = new InMemoryConversation();
        conversation.addAllContent(List.of(contents));
        return conversation;
    }

    private static SkillData skill(String name, String description, String text) {
        return new SkillData(name, description, null, null, null, null, text, Map.of());
    }

    private static ModelRequest textRequest(String text) {
        ModelRequest request = new ModelRequest();
        request.addInput(new InputTextContent(text));
        return request;
    }

    private static ModelRequest toolRequest(String text) throws NoSuchMethodException {
        ModelRequest request = new ModelRequest();
        request.attachTool(new SearchTool());
        request.addInput(new InputTextContent(text));
        return request;
    }

    private JsonObject sendWithThinking(ModelThinkingLevel level, Long maxTokens) throws Exception {
        api.enqueueMessage(message("end_turn", text("ok")));
        ModelRequest request = textRequest("Hi");
        request.addInput(new ThinkingLevelContent(level));
        if (maxTokens != null) {
            request.addInput(new MaxOutputTokensContent(maxTokens));
        }
        takeStep(request);
        return lastMessageRequest();
    }

    private List<ModelOutput> takeStep(ModelRequest request) throws Descriptor.FormException {
        return client.takeStep(modelData, settings, new AnthropicClientSettings(), request, inputs(request));
    }

    private static List<ModelInput> inputs(ModelRequest request) {
        return request.getModelInputs();
    }

    /** Mirrors the step loop of ModelRequest.execute, which needs a running Jenkins to find the client. */
    private List<ModelOutput> execute(ModelRequest request, Function<ToolCallContent, String> toolHandler)
            throws Descriptor.FormException {
        List<ModelOutput> all = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            List<ModelOutput> step = takeStep(request);
            if (step.isEmpty()) {
                return all;
            }
            all.addAll(step);
            for (ModelOutput output : step) {
                if (output instanceof ToolCallContent call) {
                    request.addInput(
                            new ToolCallResponseContent(call.getToolUseId(), true, toolHandler.apply(call), null));
                }
            }
        }
        fail("Request did not finish");
        return all;
    }

    private JsonObject lastMessageRequest() {
        List<FakeAnthropicApi.RecordedRequest> requests = api.messageRequests();
        return requests.get(requests.size() - 1).json();
    }

    private static <T> T single(List<ModelOutput> outputs, Class<T> type) {
        List<T> matches =
                outputs.stream().filter(type::isInstance).map(type::cast).toList();
        assertEquals(1, matches.size(), "Expected exactly one " + type.getSimpleName() + " in " + outputs);
        return matches.get(0);
    }

    private static List<String> roles(JsonArray messages) {
        List<String> roles = new ArrayList<>();
        messages.forEach(m -> roles.add(m.getAsJsonObject().get("role").getAsString()));
        return roles;
    }

    private static String role(JsonArray messages, int index) {
        return messages.get(index).getAsJsonObject().get("role").getAsString();
    }

    private static JsonArray content(JsonArray messages, int index) {
        return messages.get(index).getAsJsonObject().getAsJsonArray("content");
    }

    private static String type(JsonArray blocks, int index) {
        return blocks.get(index).getAsJsonObject().get("type").getAsString();
    }
}
