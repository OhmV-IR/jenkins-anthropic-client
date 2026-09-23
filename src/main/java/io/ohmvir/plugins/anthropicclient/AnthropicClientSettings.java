package io.ohmvir.plugins.anthropicclient;

import hudson.Extension;
import io.ohmvir.plugins.jenkinsaisynapse.configuration.client.ModelClientConfiguration;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.DataBoundConstructor;

@Extension
public class AnthropicClientSettings extends ModelClientConfiguration {
    public static final String DEFAULT_ANTHROPIC_VERSION = "2023-06-01";
    public static final long DEFAULT_MAX_TOKENS = 4096L;

    private @Getter final String anthropicVersion;
    private @Getter final long defaultMaxTokens;

    public AnthropicClientSettings() throws FormException {
        super(120L);
        this.anthropicVersion = DEFAULT_ANTHROPIC_VERSION;
        this.defaultMaxTokens = DEFAULT_MAX_TOKENS;
    }

    @DataBoundConstructor
    public AnthropicClientSettings(long timeoutSeconds, String anthropicVersion, long defaultMaxTokens)
            throws FormException {
        super(timeoutSeconds);
        this.anthropicVersion = (anthropicVersion == null || anthropicVersion.isBlank())
                ? DEFAULT_ANTHROPIC_VERSION
                : anthropicVersion.trim();
        if (defaultMaxTokens <= 0) {
            throw new FormException("Default max tokens must be greater than zero", "defaultMaxTokens");
        }
        this.defaultMaxTokens = defaultMaxTokens;
    }

    @Override
    public @NonNull String getDisplayName() {
        return "Anthropic Client Settings";
    }
}
