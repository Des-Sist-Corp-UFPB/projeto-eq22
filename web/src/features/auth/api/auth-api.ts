import { ApiError, apiRequest } from "@/lib/api/client";

/**
 * Exactly what the backend returns. No identifiers: the browser is never told a userId or a
 * tenantId, so it has none to send back as a claim of identity — the server resolves both from the
 * session.
 */
export type AuthenticatedSession = {
  user: { displayName: string; email: string };
  activeWorkspace: { name: string; role: string };
};

export function login(email: string, password: string) {
  return apiRequest<AuthenticatedSession>("/api/auth/login", {
    method: "POST",
    body: { email, password },
  });
}

export type RegisterInput = {
  displayName: string;
  email: string;
  password: string;
  passwordConfirmation: string;
  primaryPersona: string;
  timeZone: string;
};

export function register(input: RegisterInput) {
  return apiRequest<AuthenticatedSession>("/api/auth/register", {
    method: "POST",
    body: input,
  });
}

/**
 * Restores the session on load. Not being logged in is a normal outcome, not a failure, so 401
 * resolves to {@code null}; anything else (backend down, proxy misconfigured) stays an error so the
 * UI can tell "you need to log in" from "we cannot reach the server".
 */
export async function fetchSession(): Promise<AuthenticatedSession | null> {
  try {
    return await apiRequest<AuthenticatedSession>("/api/auth/me");
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      return null;
    }
    throw error;
  }
}

export function logout() {
  return apiRequest<void>("/api/auth/logout", { method: "POST" });
}
