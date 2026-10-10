package io.ohmvir.plugins.anthropicclient;

import static io.ohmvir.plugins.anthropicclient.FakeAnthropicApi.modelInfo;
import static org.junit.jupiter.api.Assertions.*;

import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelCapability;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelData;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelInputType;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelThinkingLevel;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnthropicModelDataRetrieverTest {
    private FakeAnthropicApi api;
    private final AnthropicModelDataRetriever retriever = new AnthropicModelDataRetriever() {
        @Override
        String resolveApiKey(AnthropicModelSettings configuration) {
            return "test-api-key";
        }
    };

    @BeforeEach
    void setUp() throws IOException {
        api = new FakeAnthropicApi();
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    @Test
    void adaptiveThinkingModelAdvertisesEffortLevelsAndNoSampling() throws Exception {
        api.setModelInfo(
                "claude-adaptive", modelInfo("claude-adaptive", 1_000_000L, 128_000L, true, false, true, false));

        ModelData data = retriever.retrieveFromConfiguration(settings("claude-adaptive"));

        assertEquals(
                List.of(
                        ModelThinkingLevel.OFF,
                        ModelThinkingLevel.ON,
                        ModelThinkingLevel.LOW,
                        ModelThinkingLevel.MEDIUM,
                        ModelThinkingLevel.HIGH,
                        ModelThinkingLevel.MAX),
                data.getSupportedThinkingLevels());
        assertTrue(data.hasCapability(ModelCapability.THINKING));
        assertFalse(data.hasCapability(ModelCapability.CUSTOM_TEMPERATURE));
        assertFalse(data.hasCapability(ModelCapability.CUSTOM_TOP_P));
        assertFalse(data.hasCapability(ModelCapability.CUSTOM_TOP_K));
        assertEquals(1_000_000L, data.getContextWindow());
        assertEquals(1_000_000L, data.getMaxInputTokens());
        assertEquals(128_000L, data.getMaxOutputTokens());
    }

    @Test
    void budgetThinkingModelAdvertisesAllLevelsAndSampling() throws Exception {
        api.setModelInfo("claude-budget", modelInfo("claude-budget", null, 64_000L, false, true, true, false));

        ModelData data = retriever.retrieveFromConfiguration(settings("claude-budget"));

        assertEquals(
                List.of(
                        ModelThinkingLevel.OFF,
                        ModelThinkingLevel.ON,
                        ModelThinkingLevel.LOW,
                        ModelThinkingLevel.MEDIUM,
                        ModelThinkingLevel.HIGH,
                        ModelThinkingLevel.EXTRA_HIGH,
                        ModelThinkingLevel.MAX),
                data.getSupportedThinkingLevels());
        assertTrue(data.hasCapability(ModelCapability.CUSTOM_TEMPERATURE));
        assertTrue(data.hasCapability(ModelCapability.CUSTOM_TOP_K));
        assertEquals(AnthropicModelDataRetriever.DEFAULT_CONTEXT_WINDOW, data.getContextWindow());
        assertTrue(data.supportsInput(ModelInputType.IMAGE));
        assertTrue(data.supportsInput(ModelInputType.FILE));
        assertTrue(data.hasCapability(ModelCapability.CITATIONS));
    }

    @Test
    void modelWithoutThinkingAdvertisesNoThinkingLevels() throws Exception {
        api.setModelInfo("claude-plain", modelInfo("claude-plain", 200_000L, 8192L, false, false, false, false));

        ModelData data = retriever.retrieveFromConfiguration(settings("claude-plain"));

        assertTrue(data.getSupportedThinkingLevels().isEmpty());
        assertFalse(data.hasCapability(ModelCapability.THINKING));
        assertTrue(data.hasCapability(ModelCapability.CUSTOM_TEMPERATURE));
    }

    @Test
    void retrievedFeaturesAreSharedWithTheClient() throws Exception {
        api.setModelInfo("claude-shared", modelInfo("claude-shared", 1_000_000L, 128_000L, true, false, true, true));

        retriever.retrieveFromConfiguration(settings("claude-shared"));

        AnthropicModelFeatures features = AnthropicModelFeatures.cached(api.baseUrl(), "claude-shared");
        assertNotNull(features);
        assertTrue(features.adaptiveThinking());
        assertFalse(features.budgetThinking());
    }

    @Test
    void unknownModelFailsWithIOException() throws Exception {
        IOException e = assertThrows(IOException.class, () -> retriever.retrieveFromConfiguration(settings("missing")));
        assertTrue(e.getMessage().contains("missing"));
    }

    private AnthropicModelSettings settings(String modelName) throws Exception {
        return new AnthropicModelSettings(modelName, "cred-id", api.baseUrl());
    }
}
