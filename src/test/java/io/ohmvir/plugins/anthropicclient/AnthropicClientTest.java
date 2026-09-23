package io.ohmvir.plugins.anthropicclient;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import org.junit.jupiter.api.Test;

public class AnthropicClientTest {

    @Test
    public void testClientSettingsDefaults() throws Descriptor.FormException {
        AnthropicClientSettings settings = new AnthropicClientSettings();
        assertEquals("2023-06-01", settings.getAnthropicVersion());
        assertEquals(4096L, settings.getDefaultMaxTokens());
        assertEquals(120L, settings.getTimeoutSeconds());
        assertEquals("Anthropic Client Settings", settings.getDisplayName());
    }

    @Test
    public void testClientSettingsCustom() throws Descriptor.FormException {
        AnthropicClientSettings settings = new AnthropicClientSettings(60L, "2024-01-01", 8192L);
        assertEquals("2024-01-01", settings.getAnthropicVersion());
        assertEquals(8192L, settings.getDefaultMaxTokens());
        assertEquals(60L, settings.getTimeoutSeconds());

        assertThrows(Descriptor.FormException.class, () -> new AnthropicClientSettings(60L, "2024-01-01", 0L));
    }

    @Test
    public void testModelSettingsSanitizeUrl() {
        assertEquals("https://api.anthropic.com", AnthropicModelSettings.sanitizeUrl(null));
        assertEquals("https://api.anthropic.com", AnthropicModelSettings.sanitizeUrl(""));
        assertEquals("https://api.anthropic.com", AnthropicModelSettings.sanitizeUrl("https://api.anthropic.com/"));
        assertEquals(
                "https://custom.gateway.com/v1",
                AnthropicModelSettings.sanitizeUrl("https://custom.gateway.com/v1///"));
    }

    @Test
    public void testModelSettingsValidation() {
        AnthropicModelSettings.DescriptorImpl desc = new AnthropicModelSettings.DescriptorImpl();
        assertEquals(FormValidation.Kind.OK, desc.doCheckApiBaseUrl("").kind);
        assertEquals(FormValidation.Kind.OK, desc.doCheckApiBaseUrl("https://api.anthropic.com").kind);
        assertEquals(FormValidation.Kind.ERROR, desc.doCheckApiBaseUrl("invalid-url-without-host").kind);
        assertEquals(FormValidation.Kind.ERROR, desc.doCheckApiBaseUrl("ftp://api.anthropic.com").kind);

        assertEquals(FormValidation.Kind.ERROR, desc.doCheckApiKeyCredentialsId("").kind);
        assertEquals(FormValidation.Kind.ERROR, desc.doCheckApiKeyCredentialsId(null).kind);
        assertEquals(FormValidation.Kind.OK, desc.doCheckApiKeyCredentialsId("dummy-id-outside-jenkins").kind);

        assertEquals(FormValidation.Kind.ERROR, desc.doCheckModelName("").kind);
        assertEquals(FormValidation.Kind.ERROR, desc.doCheckModelName(null).kind);
        assertEquals(FormValidation.Kind.OK, desc.doCheckModelName("claude-3-5-sonnet-latest").kind);
    }

    @Test
    public void testModelSettingsConstructorValidation() {
        assertThrows(
                Descriptor.FormException.class,
                () -> new AnthropicModelSettings("", "valid-id", "https://api.anthropic.com"));
        assertThrows(
                Descriptor.FormException.class,
                () -> new AnthropicModelSettings("claude-3-5-sonnet", "", "https://api.anthropic.com"));
        assertThrows(
                Descriptor.FormException.class,
                () -> new AnthropicModelSettings("claude-3-5-sonnet", null, "https://api.anthropic.com"));
        assertThrows(
            Descriptor.FormException.class,
            () -> new AnthropicModelSettings("claude-3-5-sonnet", "valid-id", "ftp://api.anthropic.com"));
    }

    @Test
    public void testModelSettingsDropdownNoFallbackWhenNoCredentials() {
        AnthropicModelSettings.DescriptorImpl desc = new AnthropicModelSettings.DescriptorImpl();
        ListBoxModel items = desc.doFillModelNameItems("", "");
        assertFalse(items.isEmpty());
        // Verify no fake fallback models are injected; instead an instructive disabled option is returned
        assertEquals(1, items.size());
        assertTrue(items.get(0).name.contains("Please select a valid API Key credential"));
    }

    @Test
    public void testModelDataRetrieverThrowsWhenCredentialsUnresolvable() {
        AnthropicModelDataRetriever retriever = new AnthropicModelDataRetriever();
        AnthropicModelSettings config;
        try {
            config = new AnthropicModelSettings();
        } catch (Descriptor.FormException e) {
            fail("Default config should not throw: " + e.getMessage());
            return;
        }

        // Must throw IOException with clear message rather than falling back or silently guessing
        assertThrows(IOException.class, () -> retriever.retrieveFromConfiguration(config));
    }
}
