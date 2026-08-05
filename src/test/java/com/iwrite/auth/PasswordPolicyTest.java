package com.iwrite.auth;

import org.junit.jupiter.api.Test;

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
}
