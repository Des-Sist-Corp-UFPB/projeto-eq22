"use client";

import { useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { Input } from "@/components/ui/input";
import { useRegister } from "@/features/auth/session";
import { ApiError } from "@/lib/api/client";

export const BACKEND_UNAVAILABLE_MESSAGE = "Não conseguimos acessar o IWrite agora. Tente novamente em instantes.";
// Matches AuthMessages/RegistrationMessages on the backend — a frontend constant instead of the
// response body itself, so this stays independent of whatever the backend happens to send.
export const RATE_LIMITED_MESSAGE = "Muitas tentativas de cadastro. Aguarde um momento e tente novamente.";

const PERSONAS: { value: string; label: string }[] = [
  { value: "WRITER", label: "Escritor(a)" },
  { value: "EDITOR", label: "Editor(a)" },
  { value: "REVIEWER", label: "Revisor(a)" },
  { value: "BETA_READER", label: "Beta reader" },
  { value: "OTHER", label: "Outro" },
];

const FALLBACK_TIME_ZONE = "America/Sao_Paulo";

// Matches users.display_name varchar(255) (backend RegistrationService, RegistrationMessages).
const MAX_DISPLAY_NAME_LENGTH = 255;

/** Best-effort browser detection with a safe, always-valid IANA fallback: registration must never
 *  fail because a sandboxed or unusual environment can't report a time zone. */
export function detectTimeZone(): string {
  try {
    const detected = Intl.DateTimeFormat().resolvedOptions().timeZone;
    return detected || FALLBACK_TIME_ZONE;
  } catch {
    return FALLBACK_TIME_ZONE;
  }
}

export function RegisterForm() {
  const registerMutation = useRegister();
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [passwordConfirmation, setPasswordConfirmation] = useState("");
  const [primaryPersona, setPrimaryPersona] = useState("WRITER");
  const [passwordVisible, setPasswordVisible] = useState(false);
  const [validationError, setValidationError] = useState<string | null>(null);
  const [timeZone] = useState(detectTimeZone);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    // Belt and braces with the disabled button: a second Enter press while the request is in
    // flight must not start another registration.
    if (registerMutation.isPending) {
      return;
    }

    const validation = validate(displayName, email, password, passwordConfirmation);
    setValidationError(validation);
    if (validation) {
      return;
    }

    registerMutation.mutate({
      displayName,
      email,
      password,
      passwordConfirmation,
      primaryPersona,
      timeZone,
    });
  }

  const errorMessage = validationError ?? failureMessage(registerMutation.error);

  return (
    <form className="grid gap-4" onSubmit={handleSubmit} noValidate>
      <div className="grid gap-1 text-sm">
        <label className="font-medium text-zinc-700" htmlFor="displayName">
          Nome de exibição
        </label>
        <Input
          id="displayName"
          name="displayName"
          type="text"
          autoComplete="name"
          autoFocus
          // No maxLength here: the DOM attribute truncates by UTF-16 code unit, not code point, so
          // it would cut off a paste of more than ~127 astral characters (e.g. emoji, each two code
          // units) well before the real 255-code-point limit `validate` below enforces correctly via
          // [...trimmedDisplayName].length. Enforcing both would just make the DOM one wrong first.
          value={displayName}
          onChange={(event) => setDisplayName(event.target.value)}
        />
      </div>

      <div className="grid gap-1 text-sm">
        <label className="font-medium text-zinc-700" htmlFor="email">
          Email
        </label>
        <Input
          id="email"
          name="email"
          type="email"
          autoComplete="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
        />
      </div>

      <div className="grid gap-1 text-sm">
        <label className="font-medium text-zinc-700" htmlFor="password">
          Senha
        </label>
        <div className="relative">
          <Input
            id="password"
            name="password"
            type={passwordVisible ? "text" : "password"}
            autoComplete="new-password"
            className="w-full pr-24"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
          <button
            type="button"
            aria-pressed={passwordVisible}
            className="absolute inset-y-0 right-0 rounded-r-md px-3 text-sm font-medium text-zinc-600 underline-offset-2 transition hover:text-zinc-900 focus-visible:underline"
            onClick={() => setPasswordVisible((visible) => !visible)}
          >
            {passwordVisible ? "Ocultar" : "Mostrar"}
            <span className="sr-only"> senha</span>
          </button>
        </div>
        <p className="text-xs text-zinc-500">Ao menos 10 caracteres, incluindo letras e números.</p>
      </div>

      <div className="grid gap-1 text-sm">
        <label className="font-medium text-zinc-700" htmlFor="passwordConfirmation">
          Confirme a senha
        </label>
        <Input
          id="passwordConfirmation"
          name="passwordConfirmation"
          type={passwordVisible ? "text" : "password"}
          autoComplete="new-password"
          value={passwordConfirmation}
          onChange={(event) => setPasswordConfirmation(event.target.value)}
        />
      </div>

      <div className="grid gap-1 text-sm">
        <label className="font-medium text-zinc-700" htmlFor="primaryPersona">
          Perfil principal
        </label>
        <select
          id="primaryPersona"
          name="primaryPersona"
          className="min-h-10 rounded-md border border-zinc-300 bg-white px-3 py-2 outline-none transition focus:border-zinc-700"
          value={primaryPersona}
          onChange={(event) => setPrimaryPersona(event.target.value)}
        >
          {PERSONAS.map((persona) => (
            <option key={persona.value} value={persona.value}>
              {persona.label}
            </option>
          ))}
        </select>
      </div>

      {errorMessage ? <FeedbackMessage variant="error">{errorMessage}</FeedbackMessage> : null}

      <Button type="submit" disabled={registerMutation.isPending}>
        {registerMutation.isPending ? "Criando conta…" : "Criar conta"}
      </Button>
    </form>
  );
}

// Mirrors the backend's PasswordPolicy, which tests Unicode letters/digits via
// Character.isLetter/Character.isDigit — not the ASCII-only [a-zA-Z]/[0-9] a plain regex would use.
const PASSWORD_HAS_LETTER = /\p{L}/u;
const PASSWORD_HAS_DIGIT = /\p{Nd}/u;

function validate(displayName: string, email: string, password: string, passwordConfirmation: string) {
  const trimmedDisplayName = displayName.trim();
  if (!trimmedDisplayName) {
    return "Informe seu nome de exibição.";
  }
  if ([...trimmedDisplayName].length > MAX_DISPLAY_NAME_LENGTH) {
    return "O nome de exibição deve ter no máximo 255 caracteres.";
  }
  if (!email.trim()) {
    return "Informe seu email.";
  }
  if (!email.includes("@")) {
    return "Informe um email válido.";
  }
  if (!password) {
    return "Informe uma senha.";
  }
  if (password.length < 10 || !PASSWORD_HAS_LETTER.test(password) || !PASSWORD_HAS_DIGIT.test(password)) {
    return "A senha deve ter ao menos 10 caracteres, incluindo letras e números.";
  }
  if (password !== passwordConfirmation) {
    return "A confirmação de senha não confere.";
  }
  return null;
}

/**
 * Unlike the login form, registration failures ARE shown as the backend reports them for 400/409:
 * distinguishing "email already used" from "weak password" helps someone fix their own input and
 * leaks nothing about anyone else's account. 429 and every other failure still collapse to fixed,
 * audited strings, exactly like the login form.
 */
function failureMessage(error: unknown) {
  if (!error) {
    return null;
  }
  if (!(error instanceof ApiError)) {
    return BACKEND_UNAVAILABLE_MESSAGE;
  }
  if (error.status === 429) {
    return RATE_LIMITED_MESSAGE;
  }
  if (error.status === 400 || error.status === 409) {
    return error.message;
  }
  return BACKEND_UNAVAILABLE_MESSAGE;
}
