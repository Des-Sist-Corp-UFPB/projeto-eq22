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
}
