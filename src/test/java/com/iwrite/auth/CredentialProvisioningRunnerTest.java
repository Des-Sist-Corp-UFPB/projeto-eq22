package com.iwrite.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one guard that keeps the runner from starting half-configured. Pure, so it needs no context:
 * the point is the decision, not the wiring.
 */
class CredentialProvisioningRunnerTest {

    private static final String EMAIL = "someone@iwrite.local";
    private static final String PASSWORD = "uma-senha-de-teste";

    @Test
    void refusesToRunWithoutBothValues() {
        assertThatThrownBy(() -> CredentialProvisioningRunner.requireBothValuesSet("", PASSWORD))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CredentialProvisioningRunner.requireBothValuesSet(EMAIL, "   "))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CredentialProvisioningRunner.requireBothValuesSet(null, PASSWORD))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CredentialProvisioningRunner.requireBothValuesSet(EMAIL, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void saysWhichVariablesToSetWithoutEchoingAnyValue() {
        assertThatThrownBy(() -> CredentialProvisioningRunner.requireBothValuesSet(EMAIL, ""))
                .hasMessageContaining("IWRITE_CREDENTIAL_PROVISIONING_EMAIL")
                .hasMessageContaining("IWRITE_CREDENTIAL_PROVISIONING_PASSWORD")
                .hasMessageContaining("IWRITE_CREDENTIAL_PROVISIONING_ENABLED=false")
                .hasMessageNotContaining(EMAIL)
                .hasMessageNotContaining(PASSWORD);
    }

    @Test
    void acceptsBothValuesSet() {
        assertThatCode(() -> CredentialProvisioningRunner.requireBothValuesSet(EMAIL, PASSWORD))
                .doesNotThrowAnyException();
    }
}
