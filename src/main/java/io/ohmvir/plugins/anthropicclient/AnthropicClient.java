package io.ohmvir.plugins.anthropicclient;

import static java.util.Map.entry;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.*;
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

    private static class RequestState {
        int lastProcessedInputCount = -1;
        boolean lastStepHadToolCalls = false;
        final List<MessageParam> assistantMessages = new ArrayList<>();
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

        // Check if this step is redundant (e.g. while-loop in ModelRequest.execute after completion)
        if (state.lastProcessedInputCount == turnInputs.size() && !state.lastStepHadToolCalls) {
            return Collections.emptyList();
        }
        state.lastProcessedInputCount = turnInputs.size();

        String apiKey = SecretsUtils.getSecretText(configuration.getApiKeyCredentialsId(), null);
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

        List<ContentBlockParam> currentUserBlocks = new ArrayList<>();
        List<MessageParam> preConversationMessages = new ArrayList<>();

        for (ModelInput input : turnInputs) {
            switch (input) {
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
                            .append(skill.getSkillName())
                            .append("\" description=\"")
                            .append(skill.getSkillDescription())
                            .append("\">\n")
                            .append(skill.getSkillText())
                            .append("\n</skill>");
                }

                case InputTextContent textContent -> {
                    currentUserBlocks.add(ContentBlockParam.ofText(textContent.getText()));
                }

                case InputImageContent imageContent -> {
                    String base64Img = imageContent.getImageBase64();
                    if (base64Img != null) {
                        ContentBlockParam imageBlock = createImageContentBlock(base64Img);
                        if (imageBlock != null) {
                            currentUserBlocks.add(imageBlock);
                        }
                    }
                }

                case InputFileContent fileContent -> {
                    ContentBlockParam docBlock = createFileContentBlock(fileContent);
                    if (docBlock != null) {
                        currentUserBlocks.add(docBlock);
                    }
                }

                case ToolCallResponseContent toolCallResponse -> {
                    String responseContent = toolCallResponse.getResponseContent();
                    ToolResultBlockParam.Builder toolResBuilder = ToolResultBlockParam.builder()
                            .toolUseId(toolCallResponse.getToolUseId())
                            .content(responseContent != null ? responseContent : "");
                    if (!toolCallResponse.isSuccessful()) {
                        toolResBuilder.isError(true);
                    }
                    currentUserBlocks.add(ContentBlockParam.ofToolResult(toolResBuilder.build()));
                }

                case InputToolContent toolContent -> {
                    toolsList.add(toolToAnthropicTool(toolContent));
                }

                case InputConversationContent conversationContent -> {
                    List<MessageParam> conversationMessages = convertConversation(conversationContent);
                    if (conversationMessages != null) {
                        preConversationMessages.addAll(conversationMessages);
                    }
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

        // Configure max tokens (mandatory in Anthropic API)
        long effectiveMaxTokens = maxTokens != null
                ? maxTokens
                : (clientConfiguration != null && clientConfiguration.getDefaultMaxTokens() > 0
                        ? clientConfiguration.getDefaultMaxTokens()
                        : AnthropicClientSettings.DEFAULT_MAX_TOKENS);

        boolean thinkingActive = false;
        long budgetTokens = 0L;
        if (thinkingLevel != null && thinkingLevel != ModelThinkingLevel.OFF) {
            thinkingActive = true;
            budgetTokens = switch (thinkingLevel) {
                case LOW -> 2048L;
                case MEDIUM -> 8192L;
                case HIGH -> 16384L;
                case EXTRA_HIGH -> 32768L;
                case MAX -> 64000L;
                default -> 4096L;
            };

            // Anthropic requires max_tokens > budget_tokens
            if (effectiveMaxTokens <= budgetTokens) {
                effectiveMaxTokens = budgetTokens + 2048L;
            }
        }

        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(Model.of(configuration.getModelName()))
                .maxTokens(effectiveMaxTokens)
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

        if (thinkingActive) {
            paramsBuilder.thinking(ThinkingConfigParam.ofEnabled(budgetTokens));
        } else {
            if (temperature != null) {
                paramsBuilder.temperature(Math.max(0.0, Math.min(1.0, temperature)));
            }
            if (topP != null) {
                paramsBuilder.topP(Math.max(0.0, Math.min(1.0, topP)));
            }
            if (topK != null) {
                paramsBuilder.topK(Math.max(1L, topK.longValue()));
            }
        }

        // Build messages list
        List<MessageParam> messages = new ArrayList<>(preConversationMessages);
        messages.addAll(state.assistantMessages);

        if (!currentUserBlocks.isEmpty()) {
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(MessageParam.Content.ofBlockParams(currentUserBlocks))
                    .build());
        } else if (messages.isEmpty()) {
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(MessageParam.Content.ofBlockParams(List.of(ContentBlockParam.ofText(""))))
                    .build());
        }

        paramsBuilder.messages(messages);

        long timeoutSec = (clientConfiguration != null && clientConfiguration.getTimeoutSeconds() > 0)
                ? clientConfiguration.getTimeoutSeconds()
                : 120L;

        com.anthropic.client.AnthropicClient anthropicClient = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(configuration.getApiBaseUrl())
                .timeout(Duration.ofSeconds(timeoutSec))
                .build();
        try {
            Message responseMessage = anthropicClient.messages().create(paramsBuilder.build());

            List<ModelOutput> allOutputs = new ArrayList<>();
            List<ToolCallContent> stepToolCalls = new ArrayList<>();
            List<ContentBlockParam> assistantBlocksToSave = new ArrayList<>();

            for (ContentBlock block : responseMessage.content()) {
                if (block.isText()) {
                    TextBlock textBlock = block.asText();
                    allOutputs.add(new OutputTextContent(textBlock.text()));
                    assistantBlocksToSave.add(ContentBlockParam.ofText(textBlock.text()));

                    textBlock.citations().ifPresent(citationsList -> {
                        for (TextCitation citation : citationsList) {
                            String citedText = citation.citedText();
                            int docIndex =
                                    citation.documentIndex().map(Long::intValue).orElse(0);
                            String docTitle = citation.documentTitle().orElse(null);
                            String fileId = citation.fileId().orElse(null);
                            int startChar = 0;
                            int endChar = 0;
                            if (citation.isCharLocation()) {
                                var charLoc = citation.asCharLocation();
                                startChar = (int) charLoc.startCharIndex();
                                endChar = (int) charLoc.endCharIndex();
                            }
                            allOutputs.add(
                                    new CitationContent(citedText, docIndex, docTitle, endChar, fileId, startChar));
                        }
                    });
                } else if (block.isThinking()) {
                    ThinkingBlock thinkingBlock = block.asThinking();
                    allOutputs.add(new ThinkingContent(thinkingBlock.thinking()));
                    assistantBlocksToSave.add(ContentBlockParam.ofThinking(ThinkingBlockParam.builder()
                            .thinking(thinkingBlock.thinking())
                            .signature(thinkingBlock.signature())
                            .build()));
                } else if (block.isToolUse()) {
                    ToolUseBlock toolUseBlock = block.asToolUse();
                    String toolUseId = toolUseBlock.id();
                    String toolName = toolUseBlock.name();
                    JsonObject inputArgs = toolUseBlock._input().isMissing()
                            ? new JsonObject()
                            : JsonParser.parseString(toolUseBlock._input().toString())
                                    .getAsJsonObject();

                    ToolCallContent toolCall = new ToolCallContent(toolUseId, inputArgs, toolName);
                    allOutputs.add(toolCall);
                    stepToolCalls.add(toolCall);

                    assistantBlocksToSave.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                            .id(toolUseId)
                            .name(toolName)
                            .input(toolUseBlock._input())
                            .build()));
                }
            }

            if (!assistantBlocksToSave.isEmpty()) {
                state.assistantMessages.add(MessageParam.builder()
                        .role(MessageParam.Role.ASSISTANT)
                        .content(MessageParam.Content.ofBlockParams(assistantBlocksToSave))
                        .build());
            }

            // Finish reason
            responseMessage.stopReason().ifPresent(stopReason -> {
                ModelFinishReason finishReason =
                        switch (stopReason.asString()) {
                            case "max_tokens" -> ModelFinishReason.TOKEN_CAP;
                            case "tool_use" -> ModelFinishReason.TOOL_CALLS;
                            case "refusal" -> ModelFinishReason.SAFEGUARD;
                            default -> ModelFinishReason.STOP;
                        };
                allOutputs.add(new FinishReasonContent(finishReason));
            });

            // Usage
            Usage usage = responseMessage.usage();
            long inputTokens = usage.inputTokens();
            long outputTokens = usage.outputTokens();
            long cacheRead = usage.cacheReadInputTokens().orElse(0L);
            allOutputs.add(new TokenUtilizationContent(inputTokens, outputTokens, cacheRead));

            state.lastStepHadToolCalls = !stepToolCalls.isEmpty();

            // Filter outputs according to request.getOutputClasses()
            Set<Class<ModelOutput>> requestedClasses = request.getOutputClasses();
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
            state.lastStepHadToolCalls = false;
            throw new IllegalStateException(
                    "Anthropic API request failed with HTTP " + e.statusCode() + ": " + e.body(), e);
        } catch (AnthropicException e) {
            state.lastStepHadToolCalls = false;
            throw new IllegalStateException("Anthropic API request failed: " + e.getMessage(), e);
        } finally {
            anthropicClient.close();
        }
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

        Base64ImageSource.MediaType anthropicMediaType = Base64ImageSource.MediaType.of(mediaType);
        Base64ImageSource source = Base64ImageSource.builder()
                .mediaType(anthropicMediaType)
                .data(data)
                .build();

        return Objects.requireNonNull(ContentBlockParam.ofImage(ImageBlockParam.builder()
                .source(ImageBlockParam.Source.ofBase64(source))
                .build()));
    }

    private ContentBlockParam createFileContentBlock(InputFileContent fileContent) {
        String contentType = fileContent.getContentType() != null ? fileContent.getContentType() : "text/plain";
        DocumentBlockParam.Builder docBuilder = DocumentBlockParam.builder();

        if (contentType.equalsIgnoreCase("application/pdf")) {
            String b64 = Base64.getEncoder().encodeToString(fileContent.getFileData());
            docBuilder.source(DocumentBlockParam.Source.ofBase64(Base64PdfSource.of(b64)));
        } else {
            String text = new String(fileContent.getFileData(), StandardCharsets.UTF_8);
            docBuilder.source(DocumentBlockParam.Source.ofText(PlainTextSource.of(text)));
        }

        if (fileContent.getFileId() != null) {
            docBuilder.title(fileContent.getFileId());
        }

        docBuilder.citations(CitationsConfigParam.builder().enabled(true).build());
        return Objects.requireNonNull(ContentBlockParam.ofDocument(docBuilder.build()));
    }

    private Tool toolToAnthropicTool(InputToolContent toolContent) {
        Tool.InputSchema.Builder schemaBuilder = Tool.InputSchema.builder();
        Tool.InputSchema.Properties.Builder propsBuilder = Tool.InputSchema.Properties.builder();
        List<String> requiredList = new ArrayList<>();

        toolContent.getTool().getArguments().forEach(arg -> {
            String typeName = TYPE_TO_ANTHROPIC_TYPE_NAME.getOrDefault(arg.getType(), "string");
            Map<String, Object> propMap = new HashMap<>();
            propMap.put("type", typeName);
            propMap.put("description", arg.getDescription());
            propsBuilder.putAdditionalProperty(arg.getName(), JsonValue.from(propMap));

            if (arg.isRequired()) {
                requiredList.add(arg.getName());
            }
        });

        schemaBuilder.properties(propsBuilder.build());
        if (!requiredList.isEmpty()) {
            schemaBuilder.required(requiredList);
        }

        return Tool.builder()
                .name(toolContent.getTool().getName())
                .description(toolContent.getTool().getDescription())
                .inputSchema(schemaBuilder.build())
                .build();
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
                role = MessageParam.Role.USER;
                block = ContentBlockParam.ofText(textContent.getText());
            } else if (item instanceof OutputTextContent textContent) {
                role = MessageParam.Role.ASSISTANT;
                block = ContentBlockParam.ofText(textContent.getText());
            } else if (item instanceof ToolCallContent toolCall) {
                role = MessageParam.Role.ASSISTANT;
                block = ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                        .id(toolCall.getToolUseId())
                        .name(toolCall.getName())
                        .input(JsonValue.from(toolCall.getToolArguments()))
                        .build());
            } else if (item instanceof ToolCallResponseContent toolRes) {
                role = MessageParam.Role.USER;
                String responseContent = toolRes.getResponseContent();
                ToolResultBlockParam.Builder toolResBuilder = ToolResultBlockParam.builder()
                        .toolUseId(toolRes.getToolUseId())
                        .content(responseContent != null ? responseContent : "");
                if (!toolRes.isSuccessful()) {
                    toolResBuilder.isError(true);
                }
                block = ContentBlockParam.ofToolResult(toolResBuilder.build());
            } else if (item instanceof InputImageContent imageContent) {
                role = MessageParam.Role.USER;
                block = createImageContentBlock(imageContent.getImageBase64());
                if (block == null) continue;
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
                messages.add(MessageParam.builder()
                        .role(currentRole)
                        .content(MessageParam.Content.ofBlockParams(currentBlocks))
                        .build());

                currentRole = role;
                currentBlocks = new ArrayList<>();
                currentBlocks.add(block);
            }
        }

        if (currentRole != null && !currentBlocks.isEmpty()) {
            messages.add(MessageParam.builder()
                    .role(currentRole)
                    .content(MessageParam.Content.ofBlockParams(currentBlocks))
                    .build());
        }

        return messages;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ModelClient<?, ?>> {
        @Override
        public @NonNull String getDisplayName() {
            return "Anthropic Client";
        }
    }
}
