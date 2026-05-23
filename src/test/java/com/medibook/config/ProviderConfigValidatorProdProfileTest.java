package com.medibook.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard: catches the regression where the prod image ships with stub provider env
 * (TELEMEDICINE_PROVIDER, AI_SUPPORT_PROVIDER, INTELLIGENCE_NLP_PROVIDER unset →
 * defaults to "stub" → validator refuses to start). This must run in CI before deploy.
 */
class ProviderConfigValidatorProdProfileTest {

    private final ApplicationContextRunner ctx = new ApplicationContextRunner()
            .withUserConfiguration(ProviderConfigValidator.class);

    @Test
    void prodWithStubProvidersFailsValidation() {
        ctx.withPropertyValues(
                "spring.profiles.active=prod",
                "app.ai.support.provider=stub",
                "app.intelligence.nlp-provider=stub",
                "app.telemedicine.provider=stub"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            ProviderConfigValidator v = context.getBean(ProviderConfigValidator.class);
            assertThatThrownByValidate(v);
        });
    }

    @Test
    void prodWithRealProvidersPasses() {
        ctx.withPropertyValues(
                "spring.profiles.active=prod",
                "app.ai.support.provider=claude-api",
                "app.intelligence.nlp-provider=claude-api",
                "app.telemedicine.provider=twilio"
        ).run(context -> {
            ProviderConfigValidator v = context.getBean(ProviderConfigValidator.class);
            v.validate();
        });
    }

    private static void assertThatThrownByValidate(ProviderConfigValidator v) {
        try {
            v.validate();
        } catch (IllegalStateException expected) {
            assertThat(expected.getMessage()).contains("stub providers in prod");
            return;
        }
        throw new AssertionError("Expected IllegalStateException from validate()");
    }
}
