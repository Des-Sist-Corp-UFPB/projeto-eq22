import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { ApiError, apiRequest } from "@/lib/api/client";

const CSRF_TOKEN = "token-de-teste";

function clearCsrfCookie() {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("api client", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    clearCsrfCookie();
  });

  afterEach(() => {
    clearCsrfCookie();
  });

  test("chama caminho relativo /api e envia o cookie de sessão", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse([]));

    await apiRequest("/api/books");

    const [url, init] = fetchMock.mock.calls[0];
    // Relative on purpose: an absolute origin here would make the request cross-site and the
    // SameSite=Lax session cookie would stop being sent.
    expect(url).toBe("/api/books");
    expect(String(url).startsWith("http")).toBe(false);
    expect(init?.credentials).toBe("same-origin");
  });

  test("não pede token CSRF em leitura", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse([]));

    await apiRequest("/api/books");

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][1]?.headers).toBeUndefined();
  });

  test("envia o header CSRF em requisições mutáveis usando o cookie existente", async () => {
    document.cookie = `XSRF-TOKEN=${CSRF_TOKEN}; path=/`;
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({ id: "book-1" }));

    await apiRequest("/api/books", { method: "POST", body: { title: "Livro" } });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][1]?.headers).toEqual({
      "Content-Type": "application/json",
      "X-XSRF-TOKEN": CSRF_TOKEN,
    });
  });

  test("busca o token quando ainda não há cookie, e só então faz a mutação", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation((input) => {
      if (String(input) === "/api/auth/csrf") {
        document.cookie = `XSRF-TOKEN=${CSRF_TOKEN}; path=/`;
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      return Promise.resolve(jsonResponse({ ok: true }));
    });

    await apiRequest("/api/auth/login", { method: "POST", body: { email: "a@b.c", password: "x" } });

    // This ordering is what makes the very first mutation - the login - possible at all.
    expect(fetchMock.mock.calls.map((call) => String(call[0]))).toEqual(["/api/auth/csrf", "/api/auth/login"]);
    expect(fetchMock.mock.calls[1][1]?.headers).toMatchObject({ "X-XSRF-TOKEN": CSRF_TOKEN });
  });

  test("converte falha em ApiError com status e mensagem do backend", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse({ messages: ["Sua sessão expirou. Entre novamente para continuar."] }, 401),
    );

    const failure = await apiRequest("/api/auth/me").catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).status).toBe(401);
    expect((failure as ApiError).message).toBe("Sua sessão expirou. Entre novamente para continuar.");
  });

  test("resolve vazio em 204", async () => {
    document.cookie = `XSRF-TOKEN=${CSRF_TOKEN}; path=/`;
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 204 }));

    await expect(apiRequest("/api/auth/logout", { method: "POST" })).resolves.toBeUndefined();
  });
});
