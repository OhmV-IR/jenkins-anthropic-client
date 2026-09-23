package io.ohmvir.plugins.anthropicclient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Describable;
import io.ohmvir.plugins.jenkinsaisynapse.configuration.models.ModelConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
public class DescriptorRegistrationTest {
    private static final List<Class<? extends Describable>> DESCRIPTOR_ABSTRACT_CLASSES_TO_TEST =
            List.of(ModelConfiguration.class);

    private static final List<Class<? extends Describable<?>>> CONCRETE_EXTENSION_IMPLEMENTATIONS_TO_TEST =
            List.of(AnthropicModelSettings.class);

    private static final List<Class<? extends Describable<?>>> STANDALONE_CONCRETE_CLASSES_TO_TEST =
            List.of(AnthropicClientSettings.class);

    @Test
    public void verifyAbstractDescriptorListsAreNotEmpty(JenkinsRule j) {
        for (var abstractClass : DESCRIPTOR_ABSTRACT_CLASSES_TO_TEST) {
            assertFalse(
                    j.jenkins.getDescriptorList(abstractClass).isEmpty(),
                    "Descriptor list for abstract class " + abstractClass.getName() + " should not be empty");
        }
    }

    @Test
    public void verifyConcreteExtensionsAreRegisteredUnderAbstractBase(JenkinsRule j) {
        for (var concreteClass : CONCRETE_EXTENSION_IMPLEMENTATIONS_TO_TEST) {
            boolean exists = j.jenkins.getExtensionList(hudson.model.Descriptor.class).stream()
                    .anyMatch(d -> d.clazz.equals(concreteClass));
            assertTrue(exists, "Expected " + concreteClass.getName() + " to have a registered Descriptor in Jenkins");
        }
    }

    @Test
    public void verifyStandaloneConcreteDescriptorsAreRegistered(JenkinsRule j) {
        for (var concreteClass : STANDALONE_CONCRETE_CLASSES_TO_TEST) {
            assertNotNull(
                    j.jenkins.getDescriptorOrDie(concreteClass),
                    "Missing standalone descriptor for " + concreteClass.getName());
        }
    }

    @Test
    public void verifyAnthropicClientDescriptorRegistered(JenkinsRule j) {
        assertNotNull(j.jenkins.getDescriptorOrDie(AnthropicClient.class), "Missing descriptor for AnthropicClient");
    }
}
