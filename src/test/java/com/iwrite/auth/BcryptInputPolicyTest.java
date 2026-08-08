package com.iwrite.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Codex P2 (fresh finding, round 7, #149): {@code String.getBytes(UTF_8)} silently substitutes any
 * unpaired surrogate with the same replacement byte instead of rejecting it, so two different
 * malformed passwords could produce identical bytes — and therefore identical bcrypt hashes, or one
 * matching the other. {@link BcryptInputPolicy} is the shared fix, reused by {@link PasswordPolicy},
 * {@link CredentialProvisioningRunner} and {@link AuthController#login} — see those classes' own
 * tests for the integration-level coverage.
 */
class BcryptInputPolicyTest {

    @Test
    void encodeRejeitaHighSurrogateIsolado() {
        assertThat(BcryptInputPolicy.encode("abc" + '\uD800' + "def")).isEmpty();
    }

    @Test
    void encodeRejeitaLowSurrogateIsolado() {
        assertThat(BcryptInputPolicy.encode("abc" + '\uDC01' + "def")).isEmpty();
    }

    @Test
    void encodeAceitaParSurrogateValido() {
        // U+1F600 ("😀") is a valid high+low surrogate pair — must encode normally, not be mistaken
        // for two lone surrogates.
        assertThat(BcryptInputPolicy.encode("abc😀def")).isPresent();
    }

    @Test
    void encodeAceitaTextoAsciiComumSemAlteracao() {
        assertThat(BcryptInputPolicy.encode("abcdef123")).contains("abcdef123".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void duasEntradasComSurrogatesIsoladosDiferentesNuncaSaoAmbasAceitas() {
        String withHighD800 = "abcdefgh1" + '\uD800';
        String withHighD801 = "abcdefgh1" + '\uD801';
        assertThat(withHighD800).isNotEqualTo(withHighD801);
        // Both malformed: encode() must never substitute either one into the same, comparable bytes.
        assertThat(BcryptInputPolicy.encode(withHighD800)).isEmpty();
        assertThat(BcryptInputPolicy.encode(withHighD801)).isEmpty();
    }

    @Test
    void isValidAceitaExatamente72BytesValidos() {
        String value = "a1" + "b".repeat(70);
        assertThat(value.getBytes(StandardCharsets.UTF_8)).hasSize(72);
        assertThat(BcryptInputPolicy.isValid(value, 72)).isTrue();
    }

    @Test
    void isValidRecusaMaisDe72Bytes() {
        String value = "a1" + "b".repeat(71);
        assertThat(value.getBytes(StandardCharsets.UTF_8)).hasSize(73);
        assertThat(BcryptInputPolicy.isValid(value, 72)).isFalse();
    }

    @Test
    void isValidRecusaSurrogateIsoladoMesmoDentroDoLimiteDeBytes() {
        assertThat(BcryptInputPolicy.isValid("abcdefgh1" + '\uD800', 72)).isFalse();
    }
}
