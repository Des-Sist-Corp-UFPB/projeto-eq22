package com.iwrite.auth;

import com.iwrite.user.repository.UserCredentialRepository;
import com.iwrite.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

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

    // Codex P2 (round 6, #149): the configured email must be normalized before this guard runs, so
    // these exercise requireAsciiEmail directly against already-normalized values (mixed case and
    // edge whitespace are EmailNormalizer's job, covered by EmailNormalizerTest and the integration
    // test below).

    @Test
    void requireAsciiEmailAceitaEmailAsciiJaNormalizado() {
        assertThatCode(() -> CredentialProvisioningRunner.requireAsciiEmail("someone@iwrite.local"))
                .doesNotThrowAnyException();
    }

    @Test
    void requireAsciiEmailRecusaEmailNaoAsciiSemEcoarOValor() {
        String nonAscii = "usuária@iwrite.local";
        assertThatThrownBy(() -> CredentialProvisioningRunner.requireAsciiEmail(nonAscii))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IWRITE_CREDENTIAL_PROVISIONING_EMAIL")
                .hasMessageNotContaining(nonAscii);
    }

    // An empty value is already refused earlier, by requireBothValuesSet (blank check) — this
    // method never sees one in practice. Empty is vacuously ASCII (no code point violates the
    // rule), so requireAsciiEmail correctly does not throw on it in isolation.
    @Test
    void requireAsciiEmailNaoLancaParaEmailVazioEmIsolamento() {
        assertThatCode(() -> CredentialProvisioningRunner.requireAsciiEmail(""))
                .doesNotThrowAnyException();
    }

    // Codex P2 (round 6, #149): bcrypt silently ignores UTF-8 bytes past the 72nd — same boundary
    // as PasswordPolicy.MAX_UTF8_BYTES, applied here since this runner calls passwordEncoder.encode
    // directly (see PasswordPolicyTest for the exhaustive byte-boundary coverage).

    @Test
    void requireBcryptSafePasswordAceitaSenhaComExatamente72Bytes() {
        String password = "a1" + "b".repeat(70);
        assertThatCode(() -> CredentialProvisioningRunner.requireBcryptSafePassword(password))
                .doesNotThrowAnyException();
    }

    @Test
    void requireBcryptSafePasswordRecusaSenhaCom73BytesSemEcoarOValor() {
        String password = "a1" + "b".repeat(71);
        assertThatThrownBy(() -> CredentialProvisioningRunner.requireBcryptSafePassword(password))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IWRITE_CREDENTIAL_PROVISIONING_PASSWORD")
                .hasMessageNotContaining(password);
    }

    // Codex P2 (fresh finding, round 7, #149): String.getBytes(UTF_8) silently substitutes an
    // unpaired surrogate instead of rejecting it — this runner calls passwordEncoder.encode
    // directly, so it needs the same BcryptInputPolicy guard PasswordPolicy now has.
    @Test
    void requireBcryptSafePasswordRecusaSenhaComSurrogateIsoladoSemEcoarOValor() {
        String password = "senha-com-surrogate-" + '\uD800';
        assertThatThrownBy(() -> CredentialProvisioningRunner.requireBcryptSafePassword(password))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IWRITE_CREDENTIAL_PROVISIONING_PASSWORD")
                .hasMessageNotContaining(password);
    }

    // Codex P2 (fresh finding, round 7, #149): run() must fail on the guard above before ever
    // reaching passwordEncoder.encode or either repository — this exercises run() itself (not just
    // the static guard) to pin that ordering.
    @Test
    void runComSenhaConfiguradaComSurrogateIsoladoNaoChamaEncodeNemRepositorios() {
        UserRepository userRepository = mock(UserRepository.class);
        UserCredentialRepository credentialRepository = mock(UserCredentialRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        CredentialProvisioningRunner runner = new CredentialProvisioningRunner(
                userRepository, credentialRepository, passwordEncoder, EMAIL, "senha-com-surrogate-" + '\uD800');

        assertThatThrownBy(() -> runner.run(null)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(userRepository, credentialRepository, passwordEncoder);
    }
}
