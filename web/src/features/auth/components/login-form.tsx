"use client";

import { useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { Input } from "@/components/ui/input";
import { useLogin } from "@/features/auth/session";
import { ApiError } from "@/lib/api/client";

export const INVALID_CREDENTIALS_MESSAGE = "Não foi possível entrar. Confira seus dados e tente novamente.";
export const BACKEND_UNAVAILABLE_MESSAGE = "Não conseguimos acessar o IWrite agora. Tente novamente em instantes.";
export const SESSION_EXPIRED_MESSAGE = "Sua sessão expirou. Entre novamente para continuar.";

export function LoginForm({ expired = false }: { expired?: boolean }) {
  const login = useLogin();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [passwordVisible, setPasswordVisible] = useState(false);
  const [validationError, setValidationError] = useState<string | null>(null);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    // Belt and braces with the disabled button: a second Enter press while the request is in
    // flight must not start another login.
    if (login.isPending) {
      return;
    }

    const validation = validate(email, password);
    setValidationError(validation);
    if (validation) {
      return;
    }

    login.mutate({ email, password });
  }

  const errorMessage = validationError ?? failureMessage(login.error);

  return (
    <form className="grid gap-4" onSubmit={handleSubmit} noValidate>
      <div className="grid gap-1 text-sm">
        <label className="font-medium text-zinc-700" htmlFor="email">
          Email
        </label>
        <Input
          id="email"
          name="email"
          type="email"
          autoComplete="email"
          autoFocus
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
            autoComplete="current-password"
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
      </div>

      {errorMessage ? <FeedbackMessage variant="error">{errorMessage}</FeedbackMessage> : null}
      {!errorMessage && expired ? <FeedbackMessage variant="info">{SESSION_EXPIRED_MESSAGE}</FeedbackMessage> : null}

      <Button type="submit" disabled={login.isPending}>
        {login.isPending ? "Entrando…" : "Entrar"}
      </Button>
    </form>
  );
}

function validate(email: string, password: string) {
  if (!email.trim()) {
    return "Informe seu email.";
  }
  if (!email.includes("@")) {
    return "Informe um email válido.";
  }
  if (!password) {
    return "Informe sua senha.";
  }
  return null;
}

/**
 * Every rejected credential collapses to one message, matching the backend: distinguishing an
 * unknown email from a wrong password would let anyone test whether an account exists. Anything
 * that is not a rejection is reported as the service being unreachable.
 */
function failureMessage(error: unknown) {
  if (!error) {
    return null;
  }
  return error instanceof ApiError && error.status === 401
    ? INVALID_CREDENTIALS_MESSAGE
    : BACKEND_UNAVAILABLE_MESSAGE;
}
