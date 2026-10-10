package io.ohmvir.plugins.anthropicclient;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonField;
import com.anthropic.models.models.CapabilitySupport;
import com.anthropic.models.models.ModelInfo;
import hudson.Extension;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.*;
import io.ohmvir.plugins.jenkinsaisynapse.utils.SecretsUtils;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class AnthropicModelDataRetriever extends ModelDataRetriever<AnthropicModelSettings> {
    private static final Logger LOGGER = Logger.getLogger(AnthropicModelDataRetriever.class.getName());
    /** Used when the Models API doesn't report max_input_tokens. */
    static final long DEFAULT_CONTEXT_WINDOW = 200000L;

    public AnthropicModelDataRetriever() {
        super(AnthropicModelSettings.class);
    }

    @Override
    public ModelData retrieveFromConfiguration(AnthropicModelSettings configuration)
            throws IOException, InterruptedException {
        String apiKey = resolveApiKey(configuration);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("Anthropic API Key Credential could not be resolved for model configuration: "
                    + configuration.getModelId());
        }

        String modelName = configuration.getModelName();
        if (modelName == null || modelName.isBlank()) {
            throw new IOException("Model name is required for model configuration: " + configuration.getModelId());
        }

        com.anthropic.client.AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(configuration.getApiBaseUrl())
                .timeout(Duration.ofSeconds(15))
                .build();

        ModelInfo modelInfo;
        try {
            modelInfo = client.models().retrieve(modelName);
        } catch (Exception e) {
            String msg = "Failed to retrieve model info for '" + modelName + "' from Anthropic API: " + e.getMessage();
            LOGGER.log(Level.SEVERE, msg, e);
            throw new IOException(msg, e);
        } finally {
            client.close();
        }

        AnthropicModelFeatures features = AnthropicModelFeatures.fromModelInfo(modelInfo);
        AnthropicModelFeatures.cache(configuration.getApiBaseUrl(), modelName, features);

        ModelData ret = new ModelData(configuration);
        var modelCapabilities = modelInfo._capabilities().asKnown().orElse(null);

        List<ModelCapability> capabilities = new ArrayList<>();
        capabilities.add(ModelCapability.TOOLS);
        capabilities.add(ModelCapability.SKILLS);
        capabilities.add(ModelCapability.ADJUSTABLE_SYSTEM_PROMPT);
        capabilities.add(ModelCapability.CONVERSATIONS);
        capabilities.add(ModelCapability.OUTPUT_TOKEN_LIMITING);
        capabilities.add(ModelCapability.PREMATURE_STOP);
        capabilities.add(ModelCapability.CUSTOM_STOP_SEQUENCES);
        capabilities.add(ModelCapability.TOKEN_USAGE_METRICS);

        if (modelCapabilities != null && isSupported(modelCapabilities._citations())) {
            capabilities.add(ModelCapability.CITATIONS);
        }
        if (features.samplingParameters()) {
            capabilities.add(ModelCapability.CUSTOM_TEMPERATURE);
            capabilities.add(ModelCapability.CUSTOM_TOP_P);
            capabilities.add(ModelCapability.CUSTOM_TOP_K);
        }

        if (features.adaptiveThinking() || features.budgetThinking()) {
            capabilities.add(ModelCapability.THINKING);
            ret.setSupportedThinkingLevels(supportedThinkingLevels(features));
        } else {
            ret.setSupportedThinkingLevels(List.of());
        }
        ret.setCapabilities(capabilities);

        // Inputs
        List<ModelInputType> inputTypes = new ArrayList<>();
        inputTypes.add(ModelInputType.TEXT);
        if (modelCapabilities != null && isSupported(modelCapabilities._imageInput())) {
            inputTypes.add(ModelInputType.IMAGE);
        }
        if (modelCapabilities != null && isSupported(modelCapabilities._pdfInput())) {
            inputTypes.add(ModelInputType.FILE);
        }
        ret.setInputs(inputTypes);

        // Outputs
        List<ModelOutputType> outputTypes = new ArrayList<>();
        outputTypes.add(ModelOutputType.UNSTRUCTURED_TEXT);
        if (modelCapabilities != null && isSupported(modelCapabilities._structuredOutputs())) {
            outputTypes.add(ModelOutputType.STRUCTURED_OUTPUT);
        }
        ret.setOutputs(outputTypes);

        // Limits
        ret.setMaxOutputTokens(modelInfo.maxTokens().orElse(null));
        modelInfo.maxInputTokens().ifPresent(ret::setMaxInputTokens);

        ret.setContextWindow(modelInfo.maxInputTokens().orElse(DEFAULT_CONTEXT_WINDOW));
        ret.setMaxTemperature(1.0d);

        return ret;
    }

    /** Resolves the API key for a model configuration. Package-private so tests can avoid a Jenkins instance. */
    String resolveApiKey(AnthropicModelSettings configuration) {
        if (jenkins.model.Jenkins.getInstanceOrNull() == null) {
            return null;
        }
        return SecretsUtils.getSecretText(configuration.getApiKeyCredentialsId(), null);
    }

    static List<ModelThinkingLevel> supportedThinkingLevels(AnthropicModelFeatures features) {
        List<ModelThinkingLevel> graded = List.of(
                ModelThinkingLevel.LOW,
                ModelThinkingLevel.MEDIUM,
                ModelThinkingLevel.HIGH,
                ModelThinkingLevel.EXTRA_HIGH,
                ModelThinkingLevel.MAX);
        List<ModelThinkingLevel> levels = new ArrayList<>();
        levels.add(ModelThinkingLevel.OFF);
        levels.add(ModelThinkingLevel.ON);
        if (features.adaptiveThinking()) {
            // Adaptive thinking depth is set through effort, so only offer the levels the model has an effort for
            for (ModelThinkingLevel level : graded) {
                if (features.supportsEffort(AnthropicClient.thinkingLevelToEffort(level))) {
                    levels.add(level);
                }
            }
        } else {
            levels.addAll(graded);
        }
        return levels;
    }

    private static boolean isSupported(JsonField<CapabilitySupport> field) {
        return field.asKnown().flatMap(s -> s._supported().asKnown()).orElse(false);
    }
}
