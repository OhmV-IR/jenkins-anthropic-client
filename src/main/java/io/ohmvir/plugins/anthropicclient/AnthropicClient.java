package io.ohmvir.plugins.anthropicclient;

import static java.util.Map.entry;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import hudson.Extension;
import hudson.model.Descriptor;
import io.ohmvir.plugins.jenkinsaisynapse.api.ModelContent;
import io.ohmvir.plugins.jenkinsaisynapse.api.client.ModelClient;
import io.ohmvir.plugins.jenkinsaisynapse.api.input.*;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelData;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelFinishReason;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelThinkingLevel;
import io.ohmvir.plugins.jenkinsaisynapse.api.output.*;
import io.ohmvir.plugins.jenkinsaisynapse.utils.SecretsUtils;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;
import org.jspecify.annotations.NonNull;

@Extension
public class AnthropicClient extends ModelClient<AnthropicModelSettings, AnthropicClientSettings> {
    private static final Logger LOGGER = Logger.getLogger(AnthropicClient.class.getName());

    /** The API rejects thinking budgets below this. */
    static final long MIN_THINKING_BUDGET_TOKENS = 1024L;

    private static final Set<String> SUPPORTED_IMAGE_MEDIA_TYPES =
            Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    private static final Map<Class<?>, String> TYPE_TO_ANTHROPIC_TYPE_NAME = Map.ofEntries(
            entry(String.class, "string"),
            entry(UUID.class, "string"),
            entry(char.class, "string"),
            entry(Character.class, "string"),
            entry(int.class, "integer"),
            entry(Integer.class, "integer"),
            entry(long.class, "integer"),
            entry(Long.class, "integer"),
            entry(short.class, "integer"),
            entry(Short.class, "integer"),
            entry(byte.class, "integer"),
            entry(Byte.class, "integer"),
            entry(float.class, "number"),
            entry(Float.class, "number"),
            entry(double.class, "number"),
            entry(Double.class, "number"),
            entry(BigDecimal.class, "number"),
            entry(BigInteger.class, "integer"),
            entry(boolean.class, "boolean"),
            entry(Boolean.class, "boolean"),
            entry(List.class, "array"),
            entry(Collection.class, "array"),
            entry(Object.class, "object"),
            entry(Map.class, "object"));

    /**
     * One model response within a request. {@code inputCount} is the number of request inputs that had been sent when
     * the response was produced, so the response is replayed right after those inputs; the inputs added afterwards
     * (e.g. tool results) follow it.
     */
    private record Step(int inputCount, MessageParam assistantMessage) {}

    private static class RequestState {
        final List<Step> steps = new ArrayList<>();
    }

    private final Map<ModelRequest, RequestState> requestStates = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public List<ModelOutput> takeStepImpl(
            ModelData modelData,
            AnthropicModelSettings configuration,
            AnthropicClientSettings clientConfiguration,
            ModelRequest request,
            List<ModelInput> turnInputs) {

        RequestState state = requestStates.computeIfAbsent(request, r -> new RequestState());

        // No inputs were added since the last response, so there is nothing new to respond to (e.g. the while-loop in
        // ModelRequest.execute asking for another step after a response without tool calls).
        if (!state.steps.isEmpty() && state.steps.getLast().inputCount() >= turnInputs.size()) {
            return Collections.emptyList();
        }

        String apiKey = resolveApiKey(configuration);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Anthropic API key credential could not be resolved for model configuration: "
                            + configuration.getModelId());
        }

        StringBuilder systemPromptBuilder = new StringBuilder();
        List<Tool> toolsList = new ArrayList<>();
        List<String> stopSequences = new ArrayList<>();
        Double temperature = null;
        Double topP = null;
        Integer topK = null;
        Long maxTokens = null;
        ModelThinkingLevel thinkingLevel = null;

        List<MessageParam> preConversationMessages = new ArrayList<>();
        List<MessageParam> messages = new ArrayList<>();
        List<ContentBlockParam> currentUserBlocks = new ArrayList<>();
        int nextStep = 0;

        for (int i = 0; i <= turnInputs.size(); i++) {
            // Replay each earlier response at the point in the inputs where it was produced
            while (nextStep < state.steps.size() && state.steps.get(nextStep).inputCount() == i) {
                addUserMessage(messages, currentUserBlocks);
                currentUserBlocks = new ArrayList<>();
                MessageParam assistantMessage = state.steps.get(nextStep).assistantMessage();
                if (assistantMessage != null) {
                    messages.add(assistantMessage);
                }
                nextStep++;
            }
            if (i == turnInputs.size()) {
                break;
            }

            switch (turnInputs.get(i)) {
                case SystemPromptContent systemPromptContent -> {
                    if (!systemPromptBuilder.isEmpty()) {
                        systemPromptBuilder.append("\n\n");
                    }
                    systemPromptBuilder.append(systemPromptContent.getSystemPrompt());
                }

                case InputSkillContent skillContent -> {
                    var skill = skillContent.getSkill();
                    if (!systemPromptBuilder.isEmpty()) {
                        systemPromptBuilder.append("\n\n");
                    }
                    systemPromptBuilder
                            .append("<skill name=\"")
                            .append(escapeXmlAttribute(skill.getSkillName()))
                            .append("\" description=\"")
                            .append(escapeXmlAttribute(skill.getSkillDescription()))
                            .append("\">\n")
                            .append(skill.getSkillText())
                            .append("\n</skill>");
                }

                case InputTextContent textContent -> {
                    // The API rejects empty text blocks
                    if (textContent.getText() != null && !textContent.getText().isEmpty()) {
                        currentUserBlocks.add(ContentBlockParam.ofText(textContent.getText()));
                    }
                }

                case InputImageContent imageContent -> {
                    String base64Img = imageContent.getImageBase64();
                    if (base64Img != null) {
                        currentUserBlocks.add(createImageContentBlock(base64Img));
                    }
                }

                case InputFileContent fileContent -> {
                    ContentBlockParam docBlock = createFileContentBlock(fileContent);
                    if (docBlock != null) {
                        currentUserBlocks.add(docBlock);
                    }
                }

                case ToolCallResponseContent toolCallResponse -> {
                    currentUserBlocks.add(createToolResultBlock(toolCallResponse));
                }

                case InputToolContent toolContent -> {
                    toolsList.add(toolToAnthropicTool(toolContent));
                }

                case InputConversationContent conversationContent -> {
                    preConversationMessages.addAll(convertConversation(conversationContent));
                }

                case MaxOutputTokensContent maxOutputTokensContent -> {
                    maxTokens = maxOutputTokensContent.getMaxOutputTokens();
                }

                case StopSequencesContent stopSequencesContent -> {
                    stopSequences.addAll(stopSequencesContent.getStopPhrases());
                }

                case TemperatureContent temperatureContent -> {
                    temperature = temperatureContent.getTemperature();
                }

                case TopPContent topPContent -> {
                    topP = topPContent.getTopP();
                }

                case TopKContent topKContent -> {
                    topK = (int) Math.round(topKContent.getTopK());
                }

                case ThinkingLevelContent thinkingLevelContent -> {
                    thinkingLevel = thinkingLevelContent.getThinkingLevel();
                }

                default -> {}
            }
        }
        addUserMessage(messages, currentUserBlocks);
        messages.addAll(0, preConversationMessages);

        if (messages.isEmpty()) {
            throw new IllegalStateException("Anthropic request for model configuration " + configuration.getModelId()
                    + " has no message content to send");
        }
        if (!state.steps.isEmpty() && messages.getLast().role().equals(MessageParam.Role.ASSISTANT)) {
            // Only non-message inputs were added since the last response; there is still nothing to respond to.
            return Collections.emptyList();
        }

        long timeoutSec = (clientConfiguration != null && clientConfiguration.getTimeoutSeconds() != null)
                ? clientConfiguration.getTimeoutSeconds()
                : 120L;

        com.anthropic.client.AnthropicClient anthropicClient = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(configuration.getApiBaseUrl())
                // 0 means no timeout, as documented on the client settings
                .timeout(Duration.ofSeconds(timeoutSec))
                .build();
        try {
            // Configure max tokens (mandatory in Anthropic API)
            long effectiveMaxTokens = maxTokens != null
                    ? maxTokens
                    : (clientConfiguration != null && clientConfiguration.getDefaultMaxTokens() > 0
                            ? clientConfiguration.getDefaultMaxTokens()
                            : AnthropicClientSettings.DEFAULT_MAX_TOKENS);
            Long modelMaxTokens = modelData != null ? modelData.getMaxOutputTokens() : null;
            if (modelMaxTokens != null && modelMaxTokens > 0) {
                effectiveMaxTokens = Math.min(effectiveMaxTokens, modelMaxTokens);
            }

            MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                    .model(Model.of(configuration.getModelName()))
                    .putAdditionalHeader(
                            "anthropic-version",
                            clientConfiguration != null
                                    ? clientConfiguration.getAnthropicVersion()
                                    : AnthropicClientSettings.DEFAULT_ANTHROPIC_VERSION);

            if (!systemPromptBuilder.isEmpty()) {
                paramsBuilder.system(systemPromptBuilder.toString());
            }

            for (Tool tool : toolsList) {
                paramsBuilder.addTool(tool);
            }

            if (!stopSequences.isEmpty()) {
                paramsBuilder.stopSequences(stopSequences);
            }

            boolean thinkingActive = thinkingLevel != null && thinkingLevel != ModelThinkingLevel.OFF;
            AnthropicModelFeatures features = (thinkingActive || temperature != null || topP != null || topK != null)
                    ? AnthropicModelFeatures.lookup(
                            anthropicClient, configuration.getApiBaseUrl(), configuration.getModelName())
                    : AnthropicModelFeatures.LEGACY;

            if (thinkingActive && features.adaptiveThinking()) {
                // budget_tokens is rejected on models with adaptive thinking; depth is controlled by effort instead.
                paramsBuilder.thinking(ThinkingConfigAdaptive.builder()
                        .display(ThinkingConfigAdaptive.Display.SUMMARIZED)
                        .build());
                OutputConfig.Effort effort = thinkingLevelToEffort(thinkingLevel);
                if (effort != null && features.supportsEffort(effort)) {
                    paramsBuilder.outputConfig(
                            OutputConfig.builder().effort(effort).build());
                }
            } else if (thinkingActive && features.budgetThinking()) {
                long budgetTokens = thinkingLevelToBudget(thinkingLevel);
                // The API requires budget_tokens < max_tokens. Grow max_tokens to fit the budget unless the caller
                // capped it explicitly or the model can't output that much, in which case shrink the budget instead.
                if (effectiveMaxTokens <= budgetTokens && maxTokens == null) {
                    effectiveMaxTokens = budgetTokens + 2048L;
                    if (modelMaxTokens != null && modelMaxTokens > 0) {
                        effectiveMaxTokens = Math.min(effectiveMaxTokens, modelMaxTokens);
                    }
                }
                if (effectiveMaxTokens <= budgetTokens) {
                    budgetTokens = effectiveMaxTokens - 1;
                }
                if (budgetTokens < MIN_THINKING_BUDGET_TOKENS) {
                    budgetTokens = MIN_THINKING_BUDGET_TOKENS;
                    effectiveMaxTokens = Math.max(effectiveMaxTokens, budgetTokens + 1);
                }
                paramsBuilder.thinking(ThinkingConfigParam.ofEnabled(budgetTokens));
            } else if (thinkingActive) {
                LOGGER.warning("Model " + configuration.getModelName()
                        + " does not support thinking; ignoring thinking level " + thinkingLevel);
            }
            paramsBuilder.maxTokens(effectiveMaxTokens);

            // Sampling parameters are incompatible with thinking, and rejected outright on newer models
            if (!thinkingActive && features.samplingParameters()) {
                if (temperature != null) {
                    paramsBuilder.temperature(Math.max(0.0, Math.min(1.0, temperature)));
                }
                if (topP != null) {
                    if (temperature != null) {
                        // Current models reject requests that set both temperature and top_p
                        LOGGER.warning("Both temperature and top_p were requested; only temperature is sent");
                    } else {
                        paramsBuilder.topP(Math.max(0.0, Math.min(1.0, topP)));
                    }
                }
                if (topK != null) {
                    paramsBuilder.topK(Math.max(1L, topK.longValue()));
                }
            } else if (temperature != null || topP != null || topK != null) {
                LOGGER.warning("Sampling parameters are not supported "
                        + (thinkingActive ? "together with thinking" : "by model " + configuration.getModelName())
                        + " and were not sent");
            }

            paramsBuilder.messages(messages);

            Message responseMessage = anthropicClient.messages().create(paramsBuilder.build());

            List<ModelOutput> allOutputs = new ArrayList<>();
            List<ContentBlockParam> assistantBlocksToSave = new ArrayList<>();

            for (ContentBlock block : responseMessage.content()) {
                // Replay every block unchanged, thinking/redacted thinking included: the API requires them to be
                // passed back with their signatures when continuing after tool use.
                assistantBlocksToSave.add(block.toParam());

                if (block.isText()) {
                    TextBlock textBlock = block.asText();
                    allOutputs.add(new OutputTextContent(textBlock.text()));

                    textBlock.citations().ifPresent(citationsList -> {
                        for (TextCitation citation : citationsList) {
                            allOutputs.add(toCitationContent(citation));
                        }
                    });
                } else if (block.isThinking()) {
                    String thinking = block.asThinking().thinking();
                    if (!thinking.isEmpty()) {
                        allOutputs.add(new ThinkingContent(thinking));
                    }
                } else if (block.isToolUse()) {
                    ToolUseBlock toolUseBlock = block.asToolUse();
                    allOutputs.add(new ToolCallContent(
                            toolUseBlock.id(), toGsonObject(toolUseBlock._input()), toolUseBlock.name()));
                }
            }

            state.steps.add(new Step(
                    turnInputs.size(),
                    assistantBlocksToSave.isEmpty()
                            ? null
                            : MessageParam.builder()
                                    .role(MessageParam.Role.ASSISTANT)
                                    .content(MessageParam.Content.ofBlockParams(assistantBlocksToSave))
                                    .build()));

            responseMessage
                    .stopReason()
                    .ifPresent(stopReason -> allOutputs.add(new FinishReasonContent(toFinishReason(stopReason))));

            // input_tokens excludes cached tokens, which are reported separately
            Usage usage = responseMessage.usage();
            long cacheRead = usage.cacheReadInputTokens().orElse(0L);
            long cacheCreation = usage.cacheCreationInputTokens().orElse(0L);
            allOutputs.add(new TokenUtilizationContent(
                    usage.inputTokens() + cacheRead + cacheCreation, usage.outputTokens(), cacheRead));

            // Filter outputs according to request.getOutputClasses()
            Set<Class<? extends ModelOutput>> requestedClasses = request.getOutputClasses();
            if (requestedClasses == null || requestedClasses.isEmpty()) {
                return allOutputs;
            }

            List<ModelOutput> filteredOutputs = new ArrayList<>();
            for (ModelOutput output : allOutputs) {
                // Always preserve ToolCallContent so ModelRequest.execute can process tools
                if (output instanceof ToolCallContent) {
                    filteredOutputs.add(output);
                } else if (requestedClasses.stream().anyMatch(rc -> rc.isInstance(output))) {
                    filteredOutputs.add(output);
                }
            }
            return filteredOutputs;

        } catch (AnthropicServiceException e) {
            throw new IllegalStateException(
                    "Anthropic API request failed with HTTP " + e.statusCode() + ": " + e.body(), e);
        } catch (AnthropicException e) {
            throw new IllegalStateException("Anthropic API request failed: " + e.getMessage(), e);
        } finally {
            anthropicClient.close();
        }
    }

    /** Resolves the API key for a model configuration. Package-private so tests can avoid a Jenkins instance. */
    String resolveApiKey(AnthropicModelSettings configuration) {
        return SecretsUtils.getSecretText(configuration.getApiKeyCredentialsId(), null);
    }

    static ModelFinishReason toFinishReason(StopReason stopReason) {
        return switch (stopReason.asString()) {
            case "max_tokens", "model_context_window_exceeded" -> ModelFinishReason.TOKEN_CAP;
            case "tool_use" -> ModelFinishReason.TOOL_CALLS;
            case "refusal" -> ModelFinishReason.SAFEGUARD;
            default -> ModelFinishReason.STOP;
        };
    }

    static long thinkingLevelToBudget(ModelThinkingLevel thinkingLevel) {
        return switch (thinkingLevel) {
            case LOW -> 2048L;
            case MEDIUM -> 8192L;
            case HIGH -> 16384L;
            case EXTRA_HIGH -> 32768L;
            case MAX -> 64000L;
            default -> 4096L;
        };
    }

    static OutputConfig.Effort thinkingLevelToEffort(ModelThinkingLevel thinkingLevel) {
        return switch (thinkingLevel) {
            case LOW -> OutputConfig.Effort.LOW;
            case MEDIUM -> OutputConfig.Effort.MEDIUM;
            case HIGH -> OutputConfig.Effort.HIGH;
            case EXTRA_HIGH -> OutputConfig.Effort.XHIGH;
            case MAX -> OutputConfig.Effort.MAX;
            // ON leaves the depth to the model's default effort
            default -> null;
        };
    }

    private static CitationContent toCitationContent(TextCitation citation) {
        String citedText = citation.citedText();
        int docIndex = citation.documentIndex().map(Long::intValue).orElse(0);
        String docTitle = citation.documentTitle().orElse(null);
        String fileId = citation.fileId().orElse(null);
        int startChar = 0;
        int endChar = 0;
        if (citation.isCharLocation()) {
            var charLoc = citation.asCharLocation();
            startChar = (int) charLoc.startCharIndex();
            endChar = (int) charLoc.endCharIndex();
        }
        return new CitationContent(citedText, docIndex, docTitle, endChar, fileId, startChar);
    }

    /**
     * Adds the blocks as a user message. Tool results are moved to the front because the API requires them to come
     * before any other content in the message that answers a tool use.
     */
    private static void addUserMessage(List<MessageParam> messages, List<ContentBlockParam> blocks) {
        if (blocks.isEmpty()) {
            return;
        }
        List<ContentBlockParam> ordered = new ArrayList<>(blocks.size());
        blocks.stream().filter(ContentBlockParam::isToolResult).forEach(ordered::add);
        blocks.stream().filter(b -> !b.isToolResult()).forEach(ordered::add);
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(MessageParam.Content.ofBlockParams(ordered))
                .build());
    }

    /** Tool inputs are JSON objects; JsonValue.toString() is not JSON, so convert through Jackson. */
    static JsonObject toGsonObject(JsonValue value) {
        if (value == null || value.isMissing() || value.isNull()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(ObjectMappers.jsonMapper().writeValueAsString(value))
                    .getAsJsonObject();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize tool input: " + e.getMessage(), e);
        }
    }

    static JsonValue toJsonValue(JsonObject object) {
        if (object == null) {
            return JsonValue.from(Map.of());
        }
        try {
            return ObjectMappers.jsonMapper().readValue(object.toString(), JsonValue.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not parse tool input: " + e.getMessage(), e);
        }
    }

    private static ContentBlockParam createToolResultBlock(ToolCallResponseContent toolCallResponse) {
        String responseContent = toolCallResponse.getResponseContent();
        ToolResultBlockParam.Builder toolResBuilder =
                ToolResultBlockParam.builder().toolUseId(toolCallResponse.getToolUseId());
        if (toolCallResponse.isSuccessful()) {
            toolResBuilder.content(responseContent != null ? responseContent : "");
        } else {
            toolResBuilder
                    .content(
                            responseContent != null && !responseContent.isEmpty()
                                    ? responseContent
                                    : "Tool call failed")
                    .isError(true);
        }
        return ContentBlockParam.ofToolResult(toolResBuilder.build());
    }

    private ContentBlockParam createImageContentBlock(String base64Img) {
        String mediaType = "image/png";
        String data = base64Img;

        if (base64Img.startsWith("data:")) {
            int semiIdx = base64Img.indexOf(';');
            int commaIdx = base64Img.indexOf(',');
            if (semiIdx > 5 && commaIdx > semiIdx) {
                mediaType = base64Img.substring(5, semiIdx);
                data = base64Img.substring(commaIdx + 1);
            }
        }

        return createImageContentBlock(mediaType, data);
    }

    private static ContentBlockParam createImageContentBlock(String mediaType, String base64Data) {
        Base64ImageSource source = Base64ImageSource.builder()
                .mediaType(Base64ImageSource.MediaType.of(mediaType))
                .data(base64Data)
                .build();
        return ContentBlockParam.ofImage(ImageBlockParam.builder()
                .source(ImageBlockParam.Source.ofBase64(source))
                .build());
    }

    private ContentBlockParam createFileContentBlock(InputFileContent fileContent) {
        byte[] fileData = fileContent.getFileData();
        if (fileData == null || fileData.length == 0) {
            // The API rejects empty documents
            return null;
        }
        String contentType = fileContent.getContentType() != null
                ? fileContent.getContentType().split(";", 2)[0].trim().toLowerCase(Locale.ROOT)
                : "text/plain";

        if (SUPPORTED_IMAGE_MEDIA_TYPES.contains(contentType)) {
            return createImageContentBlock(contentType, Base64.getEncoder().encodeToString(fileData));
        }

        DocumentBlockParam.Builder docBuilder = DocumentBlockParam.builder();
        if (contentType.equals("application/pdf")) {
            String b64 = Base64.getEncoder().encodeToString(fileData);
            docBuilder.source(DocumentBlockParam.Source.ofBase64(Base64PdfSource.of(b64)));
        } else {
            String text = new String(fileData, StandardCharsets.UTF_8);
            docBuilder.source(DocumentBlockParam.Source.ofText(PlainTextSource.of(text)));
        }

        if (fileContent.getFileId() != null) {
            docBuilder.title(fileContent.getFileId());
        }

        docBuilder.citations(CitationsConfigParam.builder().enabled(true).build());
        return ContentBlockParam.ofDocument(docBuilder.build());
    }

    static String toJsonSchemaType(Class<?> type) {
        String typeName = TYPE_TO_ANTHROPIC_TYPE_NAME.get(type);
        if (typeName != null) {
            return typeName;
        }
        if (type.isArray() || Collection.class.isAssignableFrom(type)) {
            return "array";
        }
        if (Map.class.isAssignableFrom(type)) {
            return "object";
        }
        if (Number.class.isAssignableFrom(type)) {
            return "number";
        }
        return "string";
    }

    private Tool toolToAnthropicTool(InputToolContent toolContent) {
        Tool.InputSchema.Builder schemaBuilder = Tool.InputSchema.builder();
        Tool.InputSchema.Properties.Builder propsBuilder = Tool.InputSchema.Properties.builder();
        List<String> requiredList = new ArrayList<>();

        toolContent.getTool().getArguments().forEach(arg -> {
            Map<String, Object> propMap = new HashMap<>();
            propMap.put("type", toJsonSchemaType(arg.getType()));
            if (arg.getDescription() != null) {
                propMap.put("description", arg.getDescription());
            }
            propsBuilder.putAdditionalProperty(arg.getName(), JsonValue.from(propMap));

            if (arg.isRequired()) {
                requiredList.add(arg.getName());
            }
        });

        schemaBuilder.properties(propsBuilder.build());
        if (!requiredList.isEmpty()) {
            schemaBuilder.required(requiredList);
        }

        Tool.Builder toolBuilder =
                Tool.builder().name(toolContent.getTool().getName()).inputSchema(schemaBuilder.build());
        if (toolContent.getTool().getDescription() != null) {
            toolBuilder.description(toolContent.getTool().getDescription());
        }
        return toolBuilder.build();
    }

    private List<MessageParam> convertConversation(InputConversationContent conversationContent) {
        List<MessageParam> messages = new ArrayList<>();
        if (conversationContent.getConversation() == null) {
            return messages;
        }

        List<ModelContent> items = conversationContent.getConversation().getConversation();
        MessageParam.Role currentRole = null;
        List<ContentBlockParam> currentBlocks = new ArrayList<>();

        for (ModelContent item : items) {
            MessageParam.Role role;
            ContentBlockParam block;

            if (item instanceof InputTextContent textContent) {
                if (textContent.getText() == null || textContent.getText().isEmpty()) continue;
                role = MessageParam.Role.USER;
                block = ContentBlockParam.ofText(textContent.getText());
            } else if (item instanceof OutputTextContent textContent) {
                if (textContent.getText() == null || textContent.getText().isEmpty()) continue;
                role = MessageParam.Role.ASSISTANT;
                block = ContentBlockParam.ofText(textContent.getText());
            } else if (item instanceof ToolCallContent toolCall) {
                role = MessageParam.Role.ASSISTANT;
                block = ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                        .id(toolCall.getToolUseId())
                        .name(toolCall.getName())
                        .input(toJsonValue(toolCall.getToolArguments()))
                        .build());
            } else if (item instanceof ToolCallResponseContent toolRes) {
                role = MessageParam.Role.USER;
                block = createToolResultBlock(toolRes);
            } else if (item instanceof InputImageContent imageContent) {
                String base64Img = imageContent.getImageBase64();
                if (base64Img == null) continue;
                role = MessageParam.Role.USER;
                block = createImageContentBlock(base64Img);
            } else if (item instanceof InputFileContent fileContent) {
                role = MessageParam.Role.USER;
                block = createFileContentBlock(fileContent);
                if (block == null) continue;
            } else {
                continue;
            }

            if (currentRole == null) {
                currentRole = role;
                currentBlocks.add(block);
            } else if (currentRole.equals(role)) {
                currentBlocks.add(block);
            } else {
                addConversationMessage(messages, currentRole, currentBlocks);
                currentRole = role;
                currentBlocks = new ArrayList<>();
                currentBlocks.add(block);
            }
        }

        if (currentRole != null && !currentBlocks.isEmpty()) {
            addConversationMessage(messages, currentRole, currentBlocks);
        }

        return messages;
    }

    private static void addConversationMessage(
            List<MessageParam> messages, MessageParam.Role role, List<ContentBlockParam> blocks) {
        if (role.equals(MessageParam.Role.USER)) {
            addUserMessage(messages, blocks);
        } else {
            messages.add(MessageParam.builder()
                    .role(role)
                    .content(MessageParam.Content.ofBlockParams(blocks))
                    .build());
        }
    }

    private static String escapeXmlAttribute(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ModelClient<?, ?>> {
        @Override
        public @NonNull String getDisplayName() {
            return "Anthropic Client";
        }
    }
}
