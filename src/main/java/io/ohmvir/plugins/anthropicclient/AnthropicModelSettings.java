package io.ohmvir.plugins.anthropicclient;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.models.ModelInfo;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.ohmvir.plugins.jenkinsaisynapse.configuration.models.ModelConfiguration;
import io.ohmvir.plugins.jenkinsaisynapse.utils.SecretsUtils;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import lombok.Getter;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

@Extension
public class AnthropicModelSettings extends ModelConfiguration {
    public static final String DEFAULT_API_BASE_URL = "https://api.anthropic.com";
    private static final Logger LOGGER = Logger.getLogger(AnthropicModelSettings.class.getName());

    private final @Getter String apiKeyCredentialsId;
    private final @Getter String apiBaseUrl;

    public AnthropicModelSettings() throws Descriptor.FormException {
        super("claude-3-5-sonnet-latest", "Claude 3.5 Sonnet");
        this.apiKeyCredentialsId = null;
        this.apiBaseUrl = DEFAULT_API_BASE_URL;
    }

    public AnthropicModelSettings(String modelName, String apiKeyCredentialsId) throws Descriptor.FormException {
        this(modelName, apiKeyCredentialsId, DEFAULT_API_BASE_URL);
    }

    @DataBoundConstructor
    public AnthropicModelSettings(String modelName, String apiKeyCredentialsId, String apiBaseUrl)
            throws Descriptor.FormException {
        super(validateModelName(modelName), validateModelName(modelName));

        if (apiKeyCredentialsId == null || apiKeyCredentialsId.isBlank()) {
            throw new Descriptor.FormException("API Key Credential is required", "apiKeyCredentialsId");
        }
        if (Jenkins.getInstanceOrNull() != null && SecretsUtils.getSecretText(apiKeyCredentialsId, null) == null) {
            throw new Descriptor.FormException(
                    "apiKeyCredentialsId does not resolve to a valid string credential", "apiKeyCredentialsId");
        }
        validateApiBaseUrl(apiBaseUrl);
        this.apiKeyCredentialsId = apiKeyCredentialsId;
        this.apiBaseUrl = (apiBaseUrl == null || apiBaseUrl.isBlank()) ? DEFAULT_API_BASE_URL : sanitizeUrl(apiBaseUrl);
    }

    private static String validateModelName(String modelName) throws Descriptor.FormException {
        if (modelName == null || modelName.isBlank()) {
            throw new Descriptor.FormException("Model name is required and cannot be empty", "modelName");
        }
        return modelName.trim();
    }

    public static String sanitizeUrl(String url) {
        if (url == null) {
            return DEFAULT_API_BASE_URL;
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? DEFAULT_API_BASE_URL : trimmed;
    }

    private static void validateApiBaseUrl(String url) throws Descriptor.FormException {
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        try {
            URI uri = URI.create(url.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new Descriptor.FormException("API Base URL must be a valid HTTP/HTTPS URL", "apiBaseUrl");
            }
        } catch (IllegalArgumentException e) {
            throw new Descriptor.FormException("Invalid API Base URL: " + e.getMessage(), "apiBaseUrl");
        }
    }

    @Override
    public String getProviderType() {
        return "anthropic";
    }

    @Extension
    public static class DescriptorImpl extends ModelConfiguration.DescriptorImpl {
        @Override
        public @NonNull String getDisplayName() {
            return "Anthropic Model";
        }

        public ListBoxModel doFillModelNameItems(
                @QueryParameter String apiKeyCredentialsId, @QueryParameter String apiBaseUrl) {
            ListBoxModel models = new ListBoxModel();
            if (apiKeyCredentialsId == null || apiKeyCredentialsId.isBlank()) {
                models.add(new ListBoxModel.Option(
                        "Please select a valid API Key credential to populate models", "", true));
                return models;
            }

            String secretKey = (Jenkins.getInstanceOrNull() != null)
                    ? SecretsUtils.getSecretText(apiKeyCredentialsId, null)
                    : null;
            String baseUrl = sanitizeUrl(apiBaseUrl);

            if (secretKey == null || secretKey.isBlank()) {
                models.add(new ListBoxModel.Option(
                        "Please select a valid API Key credential to populate models", "", true));
                return models;
            }

            try {
                com.anthropic.client.AnthropicClient client = AnthropicOkHttpClient.builder()
                        .apiKey(secretKey)
                        .baseUrl(baseUrl)
                        .timeout(Duration.ofSeconds(10))
                        .build();

                for (ModelInfo modelInfo : client.models().list().data()) {
                    String id = modelInfo.id();
                    String name = modelInfo.displayName();
                    if (id != null && !id.isBlank()) {
                        String label = (name != null && !name.isBlank()) ? name + " (" + id + ")" : id;
                        models.add(label, id);
                    }
                }
            } catch (Exception e) {
                LOGGER.log(
                        Level.WARNING, "Failed to retrieve available models from Anthropic API: " + e.getMessage(), e);
                models.add(
                        new ListBoxModel.Option("Error retrieving models from Anthropic: " + e.getMessage(), "", true));
            }

            return models;
        }

        public ListBoxModel doFillApiKeyCredentialsIdItems(
                @AncestorInPath Item context, @QueryParameter String apiKeyCredentialsId) {
            if (Jenkins.getInstanceOrNull() == null) {
                return new StandardListBoxModel().includeCurrentValue(apiKeyCredentialsId);
            }
            if (context == null
                    ? !Jenkins.get().hasPermission(Jenkins.ADMINISTER)
                    : !context.hasPermission(Item.CONFIGURE)) {
                return new StandardListBoxModel().includeCurrentValue(apiKeyCredentialsId);
            }

            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            context,
                            StandardCredentials.class,
                            Collections.emptyList(),
                            CredentialsMatchers.instanceOf(StringCredentials.class));
        }

        @POST
        public FormValidation doCheckApiKeyCredentialsId(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("API Key Credential is required");
            }
            if (Jenkins.getInstanceOrNull() != null && SecretsUtils.getSecretText(value, null) == null) {
                return FormValidation.error(
                        "The selected credential ID could not be found or does not resolve to a valid string credential");
            }
            return FormValidation.ok();
        }

        @POST
        @Override
        public FormValidation doCheckModelName(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Model name is required and cannot be empty");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckApiBaseUrl(@QueryParameter String value) {
            try {
                validateApiBaseUrl(value);
                return FormValidation.ok();
            } catch (Descriptor.FormException e) {
                return FormValidation.error(e.getMessage());
            }
        }
    }
}
