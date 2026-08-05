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
}
