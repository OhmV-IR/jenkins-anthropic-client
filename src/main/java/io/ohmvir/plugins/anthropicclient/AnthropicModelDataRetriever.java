package io.ohmvir.plugins.anthropicclient;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
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

    public AnthropicModelDataRetriever() {
        super(AnthropicModelSettings.class);
    }

    @Override
    public ModelData retrieveFromConfiguration(AnthropicModelSettings configuration)
            throws IOException, InterruptedException {
        String apiKey = null;
        if (jenkins.model.Jenkins.getInstanceOrNull() != null) {
            apiKey = SecretsUtils.getSecretText(configuration.getApiKeyCredentialsId(), null);
        }
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
        }

        ModelData ret = new ModelData(configuration);
        var modelCapabilities = modelInfo.capabilities().orElse(null);

        List<ModelCapability> capabilities = new ArrayList<>();
        capabilities.add(ModelCapability.TOOLS);
        capabilities.add(ModelCapability.SKILLS);
        capabilities.add(ModelCapability.ADJUSTABLE_SYSTEM_PROMPT);
        capabilities.add(ModelCapability.CONVERSATIONS);
        capabilities.add(ModelCapability.OUTPUT_TOKEN_LIMITING);
        capabilities.add(ModelCapability.PREMATURE_STOP);
        capabilities.add(ModelCapability.CUSTOM_STOP_SEQUENCES);
        capabilities.add(ModelCapability.TOKEN_USAGE_METRICS);

        if (modelCapabilities != null && modelCapabilities.citations().supported()) {
            capabilities.add(ModelCapability.CITATIONS);
        }
        capabilities.add(ModelCapability.CUSTOM_TEMPERATURE);
        capabilities.add(ModelCapability.CUSTOM_TOP_P);
        capabilities.add(ModelCapability.CUSTOM_TOP_K);

        if (modelCapabilities != null && modelCapabilities.thinking().supported()) {
            capabilities.add(ModelCapability.THINKING);
            ret.setSupportedThinkingLevels(List.of(
                    ModelThinkingLevel.OFF,
                    ModelThinkingLevel.LOW,
                    ModelThinkingLevel.MEDIUM,
                    ModelThinkingLevel.HIGH,
                    ModelThinkingLevel.EXTRA_HIGH,
                    ModelThinkingLevel.MAX));
        } else {
            ret.setSupportedThinkingLevels(List.of());
        }
        ret.setCapabilities(capabilities);

        // Inputs
        List<ModelInputType> inputTypes = new ArrayList<>();
        inputTypes.add(ModelInputType.TEXT);
        if (modelCapabilities != null && modelCapabilities.imageInput().supported()) {
            inputTypes.add(ModelInputType.IMAGE);
        }
        if (modelCapabilities != null && modelCapabilities.pdfInput().supported()) {
            inputTypes.add(ModelInputType.FILE);
        }
        ret.setInputs(inputTypes);

        // Outputs
        List<ModelOutputType> outputTypes = new ArrayList<>();
        outputTypes.add(ModelOutputType.UNSTRUCTURED_TEXT);
        if (modelCapabilities != null && modelCapabilities.structuredOutputs().supported()) {
            outputTypes.add(ModelOutputType.STRUCTURED_OUTPUT);
        }
        ret.setOutputs(outputTypes);

        // Limits
        ret.setMaxOutputTokens(modelInfo.maxTokens().orElse(null));
        modelInfo.maxInputTokens().ifPresent(ret::setMaxInputTokens);

        ret.setContextWindow(200000L);
        ret.setMaxTemperature(1.0d);

        return ret;
    }
}
