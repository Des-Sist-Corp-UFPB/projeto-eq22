package com.iwrite.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    @Test
    void rejeitaSenhaCurtaMesmoComLetraENumero() {
        assertThat(PasswordPolicy.isValid("abc123")).isFalse();
    }

    @Test
    void rejeitaSenhaLongaSemNumero() {
        assertThat(PasswordPolicy.isValid("somenteletras")).isFalse();
    }

    @Test
    void rejeitaSenhaLongaSemLetra() {
        assertThat(PasswordPolicy.isValid("1234567890")).isFalse();
    }

    @Test
    void rejeitaSenhaNula() {
        assertThat(PasswordPolicy.isValid(null)).isFalse();
    }

    @Test
    void aceitaSenhaComLetraENumeroNoTamanhoMinimo() {
        assertThat(PasswordPolicy.isValid("senha12345")).isTrue();
        assertThat(PasswordPolicy.MIN_LENGTH).isEqualTo(10);
    }

    // U+10400 DESERET CAPITAL LETTER LONG A — outside the BMP, so it is a surrogate pair. Each half
    // of that pair is individually category Cs (surrogate), never Letter, so a char-by-char scan
    // (Character.isLetter(char)) never finds a letter here at all — only reading it back as one
    // code point (Character.isLetter(int)) does. Mirrors the frontend's \p{L} with the `u` flag,
    // which already matches by code point (register-form.test.tsx).
    private static final String SUPPLEMENTARY_LETTER = "𐐀";

    // U+1D7CE MATHEMATICAL BOLD DIGIT ZERO — same reasoning, category Nd only as a whole code point.
    // Mirrors the frontend's \p{Nd} with the `u` flag (register-form.test.tsx).
    private static final String SUPPLEMENTARY_DIGIT = "𝟎";

    @Test
    void aceitaLetraForaDoBmpComoUnicoCaractereClassificadoComoLetra() {
        // No BMP letters anywhere — every ASCII character here is a digit. The only letter in the
        // password is the supplementary one.
        String password = "123456789" + SUPPLEMENTARY_LETTER;
        assertThat(password).hasSizeGreaterThanOrEqualTo(PasswordPolicy.MIN_LENGTH);
        assertThat(PasswordPolicy.isValid(password)).isTrue();
    }

    @Test
    void aceitaDigitoForaDoBmpComoUnicoCaractereClassificadoComoDigito() {
        // No BMP digits anywhere — every ASCII character here is a letter. The only digit in the
        // password is the supplementary one.
        String password = "abcdefghij" + SUPPLEMENTARY_DIGIT;
        assertThat(password).hasSizeGreaterThanOrEqualTo(PasswordPolicy.MIN_LENGTH);
        assertThat(PasswordPolicy.isValid(password)).isTrue();
    }

    @Test
    void rejeitaSenhaLongaComApenasLetraForaDoBmpESemDigito() {
        // Long enough, has a letter (supplementary), but still no digit anywhere — must still fail,
        // proving the fix did not weaken the "needs both" rule while fixing code-point detection.
        String password = "aaaaaaaaa" + SUPPLEMENTARY_LETTER;
        assertThat(PasswordPolicy.isValid(password)).isFalse();
    }

    // Codex P3 (round 5): password.length() counts UTF-16 units, so a supplementary-plane character
    // (a surrogate pair) inflates the apparent length by one. These pin the minimum to code points,
    // not code units — mirrored in register-form.test.tsx with [...password].length.

    @Test
    void rejeitaSenhaComNoveCodePointsMesmoTendoDezUnidadesUtf16ViaDigitoSuplementar() {
        // 8 BMP letters + 1 supplementary digit = 9 code points, but length() == 10 (8 + 2 UTF-16
        // units for the surrogate pair) — the old length()-based check wrongly accepted this.
        String password = "abcdefgh" + SUPPLEMENTARY_DIGIT;
        assertThat(password.length()).isEqualTo(10);
        assertThat(password.codePointCount(0, password.length())).isEqualTo(9);
        assertThat(PasswordPolicy.isValid(password)).isFalse();
    }

    @Test
    void aceitaSenhaComDezCodePointsViaDigitoSuplementar() {
        // 9 BMP letters + 1 supplementary digit = 10 code points (length() == 11) — at the minimum
        // once counted correctly, must be accepted.
        String password = "abcdefghi" + SUPPLEMENTARY_DIGIT;
        assertThat(password.codePointCount(0, password.length())).isEqualTo(10);
        assertThat(PasswordPolicy.isValid(password)).isTrue();
    }

    @Test
    void rejeitaSenhaComNoveCodePointsIncluindoLetraSuplementarEDigitoBmp() {
        // 8 BMP digits + 1 supplementary letter = 9 code points (length() == 10) — same undercount,
        // this time on the letter side.
        String password = "12345678" + SUPPLEMENTARY_LETTER;
        assertThat(password.length()).isEqualTo(10);
        assertThat(password.codePointCount(0, password.length())).isEqualTo(9);
        assertThat(PasswordPolicy.isValid(password)).isFalse();
    }

    // Codex P2 (round 6, #149): DelegatingPasswordEncoder's bcrypt silently ignores any UTF-8 byte
    // past the 72nd, so two passwords sharing the same 72-byte prefix would otherwise hash
    // identically. These pin the boundary exactly at MAX_UTF8_BYTES, both for plain ASCII (1 byte
    // per code point) and Unicode (where a code point can be several bytes), mirrored in
    // register-form.test.tsx via TextEncoder.

    @Test
    void aceitaSenhaComExatamente72BytesAscii() {
        String password = "a1" + "b".repeat(70);
        assertThat(password.getBytes(StandardCharsets.UTF_8)).hasSize(72);
        assertThat(PasswordPolicy.isValid(password)).isTrue();
    }

    @Test
    void rejeitaSenhaCom73BytesAscii() {
        String password = "a1" + "b".repeat(71);
        assertThat(password.getBytes(StandardCharsets.UTF_8)).hasSize(73);
        assertThat(PasswordPolicy.isValid(password)).isFalse();
    }

    @Test
    void aceitaSenhaUnicodeComExatamente72Bytes() {
        // "ç" (U+00E7) is 2 bytes in UTF-8: 34 of them (68 bytes) + "a1" + 2 ASCII bytes = 72 bytes,
        // 36 code points — well above MIN_LENGTH.
        String password = "a1" + "ç".repeat(35);
        assertThat(password.getBytes(StandardCharsets.UTF_8)).hasSize(72);
        assertThat(PasswordPolicy.isValid(password)).isTrue();
    }

    @Test
    void rejeitaSenhaUnicodeComMaisDe72Bytes() {
        String password = "a1" + "ç".repeat(36);
        assertThat(password.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(72);
        assertThat(PasswordPolicy.isValid(password)).isFalse();
    }

    @Test
    void duasSenhasComOMesmoPrefixoDe72BytesNuncaSaoAmbasAceitasComoEquivalentes() {
        // Both exceed 72 bytes and differ only after byte 72 — the exact shape bcrypt would silently
        // collapse into the same hash. Rejecting both outright (never truncating) means neither can
        // ever be registered, so no such collision is reachable.
        String prefix = "a1" + "b".repeat(70); // 72 bytes, shared
        String passwordA = prefix + "X";
        String passwordB = prefix + "Y";
        assertThat(passwordA).isNotEqualTo(passwordB);
        assertThat(PasswordPolicy.isValid(passwordA)).isFalse();
        assertThat(PasswordPolicy.isValid(passwordB)).isFalse();
    }
}
