package com.iwrite.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailNormalizerTest {

    @Test
    void removeEspacosNasPontasEConverteParaMinusculas() {
        assertThat(EmailNormalizer.normalize(" Victim@IWrite.local ")).isEqualTo("victim@iwrite.local");
    }

    @Test
    void ehIdempotenteParaUmEmailJaNormalizado() {
        assertThat(EmailNormalizer.normalize("victim@iwrite.local")).isEqualTo("victim@iwrite.local");
    }

    @Test
    void preservaNuloEmVezDeLancar() {
        assertThat(EmailNormalizer.normalize(null)).isNull();
    }

    // #149 review, round 9: U+212A KELVIN SIGN lowercases to plain ASCII 'k' under Locale.ROOT.
    // Checking isAscii after lowercasing (the old order) would let it sail through disguised as
    // ASCII; normalize must leave it untouched (still non-ASCII) so isAscii, called on its result,
    // correctly still says no.
    private static final String KELVIN_SIGN = "K";

    @Test
    void naoConverteKelvinSignParaAsciiAoNormalizar() {
        String withKelvinSign = "user@" + KELVIN_SIGN + ".example";

        String normalized = EmailNormalizer.normalize(withKelvinSign);

        assertThat(normalized).isEqualTo(withKelvinSign);
        assertThat(EmailNormalizer.isAscii(normalized)).isFalse();
    }

    @Test
    void normalizaEmailComKMaiusculoAsciiComumParaMinusculo() {
        assertThat(EmailNormalizer.normalize("user@K.example")).isEqualTo("user@k.example");
        assertThat(EmailNormalizer.isAscii(EmailNormalizer.normalize("user@K.example"))).isTrue();
    }

    @Test
    void kelvinSignNaoColideComOEnderecoAsciiEquivalente() {
        String withKelvinSign = EmailNormalizer.normalize("user@" + KELVIN_SIGN + ".example");
        String withAsciiK = EmailNormalizer.normalize("user@K.example");

        assertThat(withKelvinSign).isNotEqualTo(withAsciiK);
    }
}
