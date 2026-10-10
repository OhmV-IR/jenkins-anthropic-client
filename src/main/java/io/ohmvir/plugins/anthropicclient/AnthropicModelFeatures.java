package io.ohmvir.plugins.anthropicclient;

import com.anthropic.core.JsonField;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.models.CapabilitySupport;
import com.anthropic.models.models.EffortCapability;
import com.anthropic.models.models.ModelCapabilities;
import com.anthropic.models.models.ModelInfo;
import com.anthropic.models.models.ThinkingCapability;
import com.anthropic.models.models.ThinkingTypes;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The request-shaping features of a model, derived from the Models API. Thinking configuration and sampling parameters
 * differ between model generations (e.g. {@code budget_tokens} and {@code temperature} are rejected with a 400 on
 * newer models), so requests must be built per model rather than with one fixed shape.
 */
record AnthropicModelFeatures(
        boolean thinking,
        boolean adaptiveThinking,
        boolean budgetThinking,
        Set<OutputConfig.Effort> effortLevels,
        boolean samplingParameters) {
    private static final Logger LOGGER = Logger.getLogger(AnthropicModelFeatures.class.getName());

    /** Used when the Models API can't be reached: the pre-adaptive request shape. */
    static final AnthropicModelFeatures LEGACY = new AnthropicModelFeatures(true, false, true, Set.of(), true);

    private static final Map<String, AnthropicModelFeatures> CACHE = new ConcurrentHashMap<>();

    static AnthropicModelFeatures fromModelInfo(ModelInfo modelInfo) {
        Optional<ModelCapabilities> capabilities = modelInfo._capabilities().asKnown();
        Optional<ThinkingCapability> thinkingCapability =
                capabilities.flatMap(c -> c._thinking().asKnown());
        boolean thinking =
                thinkingCapability.flatMap(t -> t._supported().asKnown()).orElse(false);
        Optional<ThinkingTypes> thinkingTypes =
                thinkingCapability.flatMap(t -> t._types().asKnown());
        boolean adaptive = thinking && isSupported(thinkingTypes.map(ThinkingTypes::_adaptive));
        boolean budget = thinking && isSupported(thinkingTypes.map(ThinkingTypes::_enabled));

        Set<OutputConfig.Effort> effortLevels = new HashSet<>();
        Optional<EffortCapability> effort =
                capabilities.flatMap(c -> c._effort().asKnown());
        if (effort.flatMap(e -> e._supported().asKnown()).orElse(false)) {
            addIfSupported(effortLevels, OutputConfig.Effort.LOW, effort.map(EffortCapability::_low));
            addIfSupported(effortLevels, OutputConfig.Effort.MEDIUM, effort.map(EffortCapability::_medium));
            addIfSupported(effortLevels, OutputConfig.Effort.HIGH, effort.map(EffortCapability::_high));
            addIfSupported(effortLevels, OutputConfig.Effort.XHIGH, effort.map(EffortCapability::_xhigh));
            addIfSupported(effortLevels, OutputConfig.Effort.MAX, effort.map(EffortCapability::_max));
        }

        // Every model that dropped budget_tokens thinking (Opus 4.7+, Sonnet 5+, Haiku 5.5, Fable) also rejects
        // non-default temperature / top_p / top_k. The Models API has no sampling capability field, so this is the
        // closest signal available.
        boolean sampling = !thinking || budget;
        return new AnthropicModelFeatures(thinking, adaptive, budget, Set.copyOf(effortLevels), sampling);
    }

    static AnthropicModelFeatures cached(String apiBaseUrl, String modelName) {
        return CACHE.get(cacheKey(apiBaseUrl, modelName));
    }

    static void cache(String apiBaseUrl, String modelName, AnthropicModelFeatures features) {
        CACHE.put(cacheKey(apiBaseUrl, modelName), features);
    }

    /**
     * Returns the cached features for the model, looking them up through the Models API on a cache miss and falling
     * back to {@link #LEGACY} if that lookup fails.
     */
    static AnthropicModelFeatures lookup(
            com.anthropic.client.AnthropicClient client, String apiBaseUrl, String modelName) {
        AnthropicModelFeatures features = cached(apiBaseUrl, modelName);
        if (features != null) {
            return features;
        }
        try {
            features = fromModelInfo(client.models().retrieve(modelName));
            cache(apiBaseUrl, modelName, features);
            return features;
        } catch (RuntimeException e) {
            LOGGER.log(
                    Level.WARNING,
                    "Could not retrieve model capabilities for '" + modelName
                            + "', falling back to budget-based thinking: " + e.getMessage(),
                    e);
            return LEGACY;
        }
    }

    boolean supportsEffort(OutputConfig.Effort effort) {
        return effortLevels.contains(effort);
    }

    private static String cacheKey(String apiBaseUrl, String modelName) {
        return apiBaseUrl + "|" + modelName;
    }

    private static boolean isSupported(Optional<JsonField<CapabilitySupport>> field) {
        return field.flatMap(JsonField::asKnown)
                .flatMap(s -> s._supported().asKnown())
                .orElse(false);
    }

    private static void addIfSupported(
            Set<OutputConfig.Effort> levels, OutputConfig.Effort level, Optional<JsonField<CapabilitySupport>> field) {
        if (isSupported(field)) {
            levels.add(level);
        }
    }
}
