package com.iwrite.auth;

/**
 * External registration messages, rendered as-is by the frontend. Unlike {@link AuthMessages},
 * these are allowed to be specific: telling a registrant their email is already taken or their
 * password is too weak does not enumerate anyone else's account, it explains their own input.
 */
public final class RegistrationMessages {

    public static final String INVALID_EMAIL = "Informe um email válido.";

    public static final String EMAIL_TOO_LONG = "O email deve ter no máximo 255 caracteres.";

    public static final String DISPLAY_NAME_TOO_LONG = "O nome de exibição deve ter no máximo 255 caracteres.";

    public static final String EMAIL_ALREADY_IN_USE = "Este email já está cadastrado.";

    public static final String PASSWORD_CONFIRMATION_MISMATCH = "A confirmação de senha não confere.";

    public static final String WEAK_PASSWORD =
            "A senha deve ter ao menos " + PasswordPolicy.MIN_LENGTH + " caracteres, incluindo letras e números.";

    public static final String INVALID_PERSONA = "Perfil principal inválido.";

    public static final String INVALID_TIME_ZONE = "Fuso horário inválido.";

    /** Deliberately as silent on which budget tripped as {@link AuthMessages#TOO_MANY_LOGIN_ATTEMPTS}. */
    public static final String TOO_MANY_REGISTRATION_ATTEMPTS =
            "Muitas tentativas de cadastro. Aguarde um momento e tente novamente.";

    /** Registration is refused, not queued or merged, for a caller who already holds a session:
     *  creating a second account from an authenticated session is not a supported flow. */
    public static final String ALREADY_AUTHENTICATED =
            "Você já está conectado. Saia da conta atual para cadastrar uma nova.";

    private RegistrationMessages() {
    }
}
