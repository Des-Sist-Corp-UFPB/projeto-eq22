package com.iwrite.auth;

import com.iwrite.user.entity.User;
import com.iwrite.user.entity.UserCredential;
import com.iwrite.user.repository.UserCredentialRepository;
import com.iwrite.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
    // reaching passwordEncoder.encode or either repository's write — this exercises run() itself
    // (not just the static guard) to pin that ordering. With no existing credential, this is the
    // branch that validates before creating (#149 round 10), so the user/credential read lookups
    // themselves do run first; only the write side (encode/save) must never be reached.
    @Test
    void runComSenhaConfiguradaComSurrogateIsoladoNaoChamaEncodeNemSave() {
        UserRepository userRepository = mock(UserRepository.class);
        UserCredentialRepository credentialRepository = mock(UserCredentialRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        User user = new User();
        UUID userId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", userId);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(credentialRepository.findById(userId)).thenReturn(Optional.empty());
        CredentialProvisioningRunner runner = new CredentialProvisioningRunner(
                userRepository, credentialRepository, passwordEncoder, EMAIL, "senha-com-surrogate-" + '\uD800', false);

        assertThatThrownBy(() -> runner.run(null)).isInstanceOf(IllegalStateException.class);

        verify(credentialRepository, never()).save(any());
        verifyNoInteractions(passwordEncoder);
    }

    // #149 review, round 9: U+212A KELVIN SIGN lowercases to plain ASCII 'k' under Locale.ROOT.
    // EmailNormalizer.normalize used to lowercase before requireAsciiEmail ever ran, so a configured
    // email containing it would sail through disguised as ASCII. run() must still fail safely — no
    // lookup, no encode, and the configured value never echoed — before either repository or the
    // password encoder is ever touched.
    @Test
    void runComEmailConfiguradoComKelvinSignFalhaAntesDeQualquerLookupOuEncode() {
        UserRepository userRepository = mock(UserRepository.class);
        UserCredentialRepository credentialRepository = mock(UserCredentialRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        String kelvinEmail = "user@" + 'K' + ".example";
        CredentialProvisioningRunner runner = new CredentialProvisioningRunner(
                userRepository, credentialRepository, passwordEncoder, kelvinEmail, PASSWORD, false);

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IWRITE_CREDENTIAL_PROVISIONING_EMAIL")
                .hasMessageNotContaining(kelvinEmail);

        verifyNoInteractions(userRepository, credentialRepository, passwordEncoder);
    }

    // #149 review, round 10 (fresh P2 finding): replace-existing=false must not validate or hash a
    // password that will never be used — a legacy value over the 72-byte bcrypt limit must not block
    // startup when the existing credential is simply being preserved.
    @Test
    void runComReplaceExistingFalseNaoValidaNemHasheiaSenhaQueNaoSeraUsada() {
        UserRepository userRepository = mock(UserRepository.class);
        UserCredentialRepository credentialRepository = mock(UserCredentialRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        User user = new User();
        UUID userId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", userId);
        String tooLong = "a1" + "b".repeat(71); // 73 UTF-8 bytes: never a valid bcrypt input
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(credentialRepository.findById(userId)).thenReturn(Optional.of(new UserCredential()));
        CredentialProvisioningRunner runner = new CredentialProvisioningRunner(
                userRepository, credentialRepository, passwordEncoder, EMAIL, tooLong, false);

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();

        verify(credentialRepository, never()).save(any());
        verifyNoInteractions(passwordEncoder);
    }

    // #149 review, round 10: replace-existing=true must fail on an invalid replacement password
    // before ever calling encode or save.
    @Test
    void runComReplaceExistingTrueESenhaInvalidaFalhaAntesDeEncodeOuSave() {
        UserRepository userRepository = mock(UserRepository.class);
        UserCredentialRepository credentialRepository = mock(UserCredentialRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        User user = new User();
        UUID userId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", userId);
        String tooLong = "a1" + "b".repeat(71); // 73 UTF-8 bytes
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(credentialRepository.findById(userId)).thenReturn(Optional.of(new UserCredential()));
        CredentialProvisioningRunner runner = new CredentialProvisioningRunner(
                userRepository, credentialRepository, passwordEncoder, EMAIL, tooLong, true);

        assertThatThrownBy(() -> runner.run(null)).isInstanceOf(IllegalStateException.class);

        verify(credentialRepository, never()).save(any());
        verifyNoInteractions(passwordEncoder);
    }
}
