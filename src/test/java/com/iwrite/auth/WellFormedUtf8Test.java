package com.iwrite.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #149 review, round 8: extracted from {@link BcryptInputPolicy} so a caller with nothing to do with
 * bcrypt (e.g. {@link RegistrationService}'s displayName check) can validate well-formed UTF-16
 * without coupling to a class named exclusively for password hashing.
 */
class WellFormedUtf8Test {

    @Test
    void encodeStrictRejeitaHighSurrogateIsolado() {
        assertThat(WellFormedUtf8.encodeStrict("abc" + '\uD800' + "def")).isEmpty();
    }

    @Test
    void encodeStrictRejeitaLowSurrogateIsolado() {
        assertThat(WellFormedUtf8.encodeStrict("abc" + '\uDC01' + "def")).isEmpty();
    }

    @Test
    void encodeStrictAceitaParSurrogateValido() {
        assertThat(WellFormedUtf8.encodeStrict("abc😀def")).isPresent();
    }

    @Test
    void encodeStrictAceitaTextoAsciiComumSemAlteracao() {
        assertThat(WellFormedUtf8.encodeStrict("abcdef123")).contains("abcdef123".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void isWellFormedRecusaSurrogateEmbutidoNoMeio() {
        assertThat(WellFormedUtf8.isWellFormed("Ana" + '\uD800' + "Beatriz")).isFalse();
    }

    @Test
    void isWellFormedAceitaEmojiValido() {
        assertThat(WellFormedUtf8.isWellFormed("Ana 👩‍👩‍👧‍👦")).isTrue();
    }

    @Test
    void bcryptInputPolicyDelegaParaWellFormedUtf8() {
        // Both must agree exactly: BcryptInputPolicy.encode is now a thin delegate, not a copy.
        String malformed = "abc" + '\uD800';
        assertThat(BcryptInputPolicy.encode(malformed)).isEqualTo(WellFormedUtf8.encodeStrict(malformed));
    }
}
