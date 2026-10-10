package io.ohmvir.plugins.anthropicclient;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class AnthropicModelSettingsJenkinsTest {
    private static final String CREDENTIALS_ID = "anthropic-key";

    @Test
    void modelDropdownReadsEveryPage(JenkinsRule j) throws Exception {
        addApiKey();
        try (FakeAnthropicApi api = new FakeAnthropicApi()) {
            api.setModelListPage(
                    null,
                    "{\"data\":[" + model("claude-a") + "," + model("claude-b") + "],"
                            + "\"has_more\":true,\"first_id\":\"claude-a\",\"last_id\":\"claude-b\"}");
            api.setModelListPage(
                    "claude-b",
                    "{\"data\":[" + model("claude-c") + "],"
                            + "\"has_more\":false,\"first_id\":\"claude-c\",\"last_id\":\"claude-c\"}");

            ListBoxModel items = descriptor().doFillModelNameItems(CREDENTIALS_ID, api.baseUrl());

            assertEquals(3, items.size(), items.toString());
            assertEquals("claude-c", items.get(2).value);
            assertEquals("sk-test", api.requests().get(0).headers().get("x-api-key"));
        }
    }

    @Test
    void modelDropdownDoesNotSendTheKeyForNonAdministrators(JenkinsRule j) throws Exception {
        addApiKey();
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ)
                .everywhere()
                .to("reader")
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin"));

        try (FakeAnthropicApi api = new FakeAnthropicApi()) {
            try (ACLContext ignored = ACL.as2(User.getById("reader", true).impersonate2())) {
                ListBoxModel items = descriptor().doFillModelNameItems(CREDENTIALS_ID, api.baseUrl());
                assertTrue(items.isEmpty());
                assertEquals(FormValidation.Kind.OK, descriptor().doCheckApiKeyCredentialsId("does-not-exist").kind);
            }
            assertTrue(api.requests().isEmpty(), "The API key must not be sent for a non-administrator");

            try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
                descriptor().doFillModelNameItems(CREDENTIALS_ID, api.baseUrl());
                assertEquals(FormValidation.Kind.ERROR, descriptor().doCheckApiKeyCredentialsId("does-not-exist").kind);
            }
            assertEquals(1, api.requests().size());
        }
    }

    private static AnthropicModelSettings.DescriptorImpl descriptor() {
        return new AnthropicModelSettings.DescriptorImpl();
    }

    private static void addApiKey() throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(
                        CredentialsScope.GLOBAL, CREDENTIALS_ID, "Anthropic", Secret.fromString("sk-test")));
        SystemCredentialsProvider.getInstance().save();
    }

    private static String model(String id) {
        return "{\"type\":\"model\",\"id\":\"" + id + "\",\"display_name\":\"Model " + id
                + "\",\"created_at\":\"2026-01-01T00:00:00Z\"}";
    }
}
