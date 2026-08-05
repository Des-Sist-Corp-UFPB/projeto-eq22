package com.iwrite.demo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two locks that keep known accounts out of places they must never appear. Pure, so it needs no
 * context: the point is the decision, not the wiring.
 */
class DemoDataSeederTest {

    private static final String PASSWORD = "uma-senha-de-teste";

    @Test
    void refusesToRunWhenAProductionProfileIsActive() {
        for (String profile : new String[] { "prod", "production", "PROD", "Production" }) {
            assertThatThrownBy(() -> DemoDataSeeder.requireSafeConfiguration(
                    new String[] { "demo", profile }, PASSWORD, PASSWORD))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("refuses to run")
                    .hasMessageContaining(profile);
        }
    }

    @Test
    void refusesToRunWithoutBothPasswords() {
        assertThatThrownBy(() -> DemoDataSeeder.requireSafeConfiguration(new String[] { "demo" }, "", PASSWORD))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DemoDataSeeder.requireSafeConfiguration(new String[] { "demo" }, PASSWORD, "   "))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DemoDataSeeder.requireSafeConfiguration(new String[] { "demo" }, PASSWORD, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void saysWhichVariablesToSetWithoutEchoingAnyValue() {
        assertThatThrownBy(() -> DemoDataSeeder.requireSafeConfiguration(new String[] { "demo" }, PASSWORD, ""))
                .hasMessageContaining("IWRITE_DEMO_AUTOR_A_PASSWORD")
                .hasMessageContaining("IWRITE_DEMO_AUTOR_B_PASSWORD")
                .hasMessageContaining("IWRITE_DEMO_SEED_ENABLED=false")
                // The one password that was supplied must not travel in the failure of the other.
                .hasMessageNotContaining(PASSWORD);
    }

    @Test
    void acceptsAConfiguredDemoEnvironment() {
        assertThatCode(() -> DemoDataSeeder.requireSafeConfiguration(new String[] { "demo" }, PASSWORD, PASSWORD))
                .doesNotThrowAnyException();
    }
}
