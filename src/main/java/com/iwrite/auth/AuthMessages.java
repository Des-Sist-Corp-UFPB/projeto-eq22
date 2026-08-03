package com.iwrite.auth;

/**
 * External authentication messages.
 *
 * <p>Every input failure — unknown email, wrong password, missing membership and ambiguous
 * membership — must produce {@link #INVALID_CREDENTIALS}. Distinguishing them would let a caller
 * enumerate accounts or discover that an account exists but has no (or more than one) workspace.
 */
public final class AuthMessages {

    public static final String INVALID_CREDENTIALS =
            "Não foi possível entrar. Confira seus dados e tente novamente.";

    public static final String SESSION_EXPIRED =
            "Sua sessão expirou. Entre novamente para continuar.";

    /** Deliberately silent on which dimension (origin or account) tripped, and on account existence. */
    public static final String TOO_MANY_LOGIN_ATTEMPTS =
            "Muitas tentativas de login. Aguarde um momento e tente novamente.";

    private AuthMessages() {
    }
}
