import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useEffect, type ReactElement } from "react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { QueryProvider } from "@/components/providers/query-provider";
import { LoginForm } from "@/features/auth/components/login-form";
import { SessionGuard } from "@/features/auth/components/session-guard";
import { ApiError } from "@/lib/api/client";

const authApi = vi.hoisted(() => ({ login: vi.fn(), fetchSession: vi.fn(), logout: vi.fn() }));
const booksApi = vi.hoisted(() => ({ fetchBooks: vi.fn() }));
const navigation = vi.hoisted(() => ({ replace: vi.fn(), pathname: "/library" }));
const sessionSync = vi.hoisted(() => ({ announceSpy: vi.fn() }));

vi.mock("@/features/auth/api/auth-api", () => authApi);
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: navigation.replace }),
  usePathname: () => navigation.pathname,
}));
// Keeps useSessionReconciliation (used by SessionGuard) real — only announceSessionChanged is
// wrapped, so its call count is observable without faking cross-tab delivery itself (that mechanism
// is covered end to end by session-sync.test.tsx).
vi.mock("@/features/auth/session-sync", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/features/auth/session-sync")>();
  return {
    ...actual,
    announceSessionChanged: (...args: Parameters<typeof actual.announceSessionChanged>) => {
      sessionSync.announceSpy();
      return actual.announceSessionChanged(...args);
    },
  };
});

/** Stands in for the library screen: one query, keyed exactly like the real one, scoped server-side. */
function Library() {
  const { data } = useQuery({ queryKey: ["books"], queryFn: () => booksApi.fetchBooks() });
  return <ul>{(data ?? []).map((title) => <li key={title}>{title}</li>)}</ul>;
}

/** Stands in for any other authenticated screen whose query is the one that finds the session gone. */
function FlakyWidget() {
  useQuery({ queryKey: ["notifications"], queryFn: () => Promise.reject(new ApiError("expirado", 401)), retry: false });
  return null;
}

/** A second, independent protected query that also finds the session gone — stands in for two
 *  widgets on the same screen (or a query and a mutation) both 401ing around the same moment. */
function FlakyWidgetTwo() {
  useQuery({ queryKey: ["mentions"], queryFn: () => Promise.reject(new ApiError("expirado", 401)), retry: false });
  return null;
}

/** A write in flight when a login lands — e.g. saving a scene under the outgoing account — resolved
 *  by the test whenever it chooses, so the race ("does its result repopulate the cache after the
 *  swap to B?") is observable. Mirrors session-sync.test.tsx's DelayedMutation for the login path. */
function DelayedMutation({ resolveRef }: { resolveRef: { current: (() => void) | null } }) {
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: () =>
      new Promise<void>((resolve) => {
        resolveRef.current = resolve;
      }),
    onSuccess: () => {
      queryClient.setQueryData(["books"], ["Rascunho não salvo do Autor A"]);
    },
  });
  useEffect(() => {
    mutation.mutate();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return null;
}

/** Stands in for a write action (e.g. archiving a book) whose mutation is the one that 401s. */
function ArchiveButton() {
  const mutation = useMutation({ mutationFn: () => Promise.reject(new ApiError("expirado", 401)) });
  return (
    <button type="button" onClick={() => mutation.mutate()}>
      Arquivar
    </button>
  );
}

/** Exposes the client this render tree got from QueryProvider, so tests can inspect the raw cache. */
function ClientProbe({ onClient }: { onClient: (client: QueryClient) => void }) {
  const client = useQueryClient();
  useEffect(() => {
    onClient(client);
  }, [client, onClient]);
  return null;
}

/** The two routes SessionGuard protects. Tests flip `navigation.pathname` and re-render to simulate
 *  the router.replace calls the app makes actually landing on the next screen. */
function App() {
  return <SessionGuard>{navigation.pathname === "/login" ? <LoginForm expired /> : <Library />}</SessionGuard>;
}

function renderApp(extra: ReactElement | null, onClient: (client: QueryClient) => void) {
  return render(
    <QueryProvider>
      <ClientProbe onClient={onClient} />
      <App />
      {extra}
    </QueryProvider>,
  );
}

const sessionA = {
  user: { displayName: "Autor A", email: "autor-a@iwrite.local" },
  activeWorkspace: { name: "Espaço do Autor A", role: "OWNER" },
};
const sessionB = {
  user: { displayName: "Autor B", email: "autor-b@iwrite.local" },
  activeWorkspace: { name: "Espaço do Autor B", role: "OWNER" },
};

async function loginAsB() {
  booksApi.fetchBooks.mockResolvedValue(["Livro do Autor B"]);
  authApi.login.mockResolvedValue(sessionB);

  fireEvent.change(screen.getByLabelText("Email"), { target: { value: "autor-b@iwrite.local" } });
  fireEvent.change(screen.getByLabelText("Senha"), { target: { value: "senha-b" } });
  fireEvent.click(screen.getByRole("button", { name: "Entrar" }));

  await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/library"));
}

describe("QueryProvider — limpeza de cache em 401 global", () => {
  let client: QueryClient;

  beforeEach(() => {
    vi.clearAllMocks();
    navigation.pathname = "/library";
    authApi.fetchSession.mockResolvedValue(sessionA);
    authApi.logout.mockResolvedValue(undefined);
    booksApi.fetchBooks.mockResolvedValue(["Livro do Autor A"]);
  });

  test("401 de uma query limpa o cache inteiro, preserva a sessão como null e só B aparece após novo login", async () => {
    const { rerender } = renderApp(null, (c) => (client = c));

    // Autor A autentica e carrega seus livros antes que qualquer coisa falhe.
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();
    expect(client.getQueryData(["books"])).toEqual(["Livro do Autor A"]);

    // Só então uma outra query (ex.: notificações) encontra a sessão encerrada.
    rerender(
      <QueryProvider>
        <ClientProbe onClient={(c) => (client = c)} />
        <App />
        <FlakyWidget />
      </QueryProvider>,
    );

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/login?reason=expired"));

    // Root cause, not the symptom: everything authenticated is gone — not enumerated by key — while
    // the session itself is overwritten with null rather than removed, so the guard never refetches.
    expect(client.getQueryData(["books"])).toBeUndefined();
    expect(client.getQueryData(["auth", "session"])).toBeNull();
    expect(screen.queryByText("Livro do Autor A")).not.toBeInTheDocument();

    navigation.pathname = "/login";
    rerender(
      <QueryProvider>
        <ClientProbe onClient={(c) => (client = c)} />
        <App />
      </QueryProvider>,
    );
    expect(screen.queryByText("Livro do Autor A")).not.toBeInTheDocument();

    await loginAsB();

    navigation.pathname = "/library";
    rerender(
      <QueryProvider>
        <ClientProbe onClient={(c) => (client = c)} />
        <App />
      </QueryProvider>,
    );

    expect(await screen.findByText("Livro do Autor B")).toBeInTheDocument();
    expect(screen.queryByText("Livro do Autor A")).not.toBeInTheDocument();
  });

  test("401 de uma mutation limpa o cache inteiro, preserva a sessão como null e só B aparece após novo login", async () => {
    const { rerender } = renderApp(<ArchiveButton />, (c) => (client = c));

    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Arquivar" }));

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/login?reason=expired"));
    expect(client.getQueryData(["books"])).toBeUndefined();
    expect(client.getQueryData(["auth", "session"])).toBeNull();
    expect(screen.queryByText("Livro do Autor A")).not.toBeInTheDocument();

    navigation.pathname = "/login";
    rerender(
      <QueryProvider>
        <ClientProbe onClient={(c) => (client = c)} />
        <App />
      </QueryProvider>,
    );
    expect(screen.queryByText("Livro do Autor A")).not.toBeInTheDocument();

    await loginAsB();

    navigation.pathname = "/library";
    rerender(
      <QueryProvider>
        <ClientProbe onClient={(c) => (client = c)} />
        <App />
      </QueryProvider>,
    );

    expect(await screen.findByText("Livro do Autor B")).toBeInTheDocument();
    expect(screen.queryByText("Livro do Autor A")).not.toBeInTheDocument();
  });

  test("logout explícito continua limpando o cache inteiro (comportamento preservado)", async () => {
    renderApp(null, (c) => (client = c));

    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();
    client.setQueryData(["some-other-cached-thing"], { belongs: "to Autor A" });

    fireEvent.click(screen.getByRole("button", { name: "Sair" }));

    await waitFor(() => expect(authApi.logout).toHaveBeenCalledTimes(1));
    expect(client.getQueryData(["books"])).toBeUndefined();
    expect(client.getQueryData(["some-other-cached-thing"])).toBeUndefined();
    expect(client.getQueryData(["auth", "session"])).toBeUndefined();
  });

  test("dois 401 protegidos simultâneos produzem apenas um anúncio de sessão", async () => {
    const { rerender } = renderApp(null, (c) => (client = c));

    // Autor A autentica e carrega seus livros antes que qualquer coisa falhe — como nos outros
    // testes: montar os dois widgets falhos desde o início correria com o próprio carregamento
    // inicial da biblioteca.
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();

    // Duas queries protegidas distintas encontram a sessão encerrada praticamente ao mesmo tempo.
    rerender(
      <QueryProvider>
        <ClientProbe onClient={(c) => (client = c)} />
        <App />
        <FlakyWidget />
        <FlakyWidgetTwo />
      </QueryProvider>,
    );

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/login?reason=expired"));
    // Both queries 401ed around the same moment, but only the first transition to "no session"
    // purges and announces — the second is a redundant echo of the same real-world event.
    expect(sessionSync.announceSpy).toHaveBeenCalledTimes(1);
  });

  test("401 da mutation de login sem sessão existente não encerra nem anuncia nada", async () => {
    authApi.fetchSession.mockResolvedValue(null);
    authApi.login.mockRejectedValue(new ApiError("Credenciais inválidas", 401));
    navigation.pathname = "/login";

    render(
      <QueryProvider>
        <ClientProbe onClient={(c) => (client = c)} />
        <LoginForm />
      </QueryProvider>,
    );

    fireEvent.change(screen.getByLabelText("Email"), { target: { value: "alguem@iwrite.local" } });
    fireEvent.change(screen.getByLabelText("Senha"), { target: { value: "senha-errada" } });
    fireEvent.click(screen.getByRole("button", { name: "Entrar" }));

    expect(await screen.findByText("Não foi possível entrar. Confira seus dados e tente novamente.")).toBeInTheDocument();
    expect(sessionSync.announceSpy).not.toHaveBeenCalled();
    expect(navigation.replace).not.toHaveBeenCalled();
  });

  test("401 de uma tentativa de login inválida não encerra nem anuncia a sessão A ainda válida", async () => {
    renderApp(<LoginForm />, (c) => (client = c));
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();

    authApi.login.mockRejectedValue(new ApiError("Credenciais inválidas", 401));

    fireEvent.change(screen.getByLabelText("Email"), { target: { value: "outra-conta@iwrite.local" } });
    fireEvent.change(screen.getByLabelText("Senha"), { target: { value: "senha-errada" } });
    fireEvent.click(screen.getByRole("button", { name: "Entrar" }));

    expect(await screen.findByText("Não foi possível entrar. Confira seus dados e tente novamente.")).toBeInTheDocument();

    // A's session and cache are exactly as they were — a mistyped-password attempt to switch
    // accounts is a local form error, never a reason to end an already-valid session.
    expect(sessionSync.announceSpy).not.toHaveBeenCalled();
    expect(client.getQueryData(["auth", "session"])).toEqual(sessionA);
    expect(client.getQueryData(["books"])).toEqual(["Livro do Autor A"]);
    expect(screen.getByText("Livro do Autor A")).toBeInTheDocument();
  });

  test("query antiga de A em voo não repopula o cache depois do login B", async () => {
    const { rerender } = renderApp(null, (c) => (client = c));
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();

    // A slow-resolving "books" fetch already in flight for A right as the login to B lands.
    let resolveStaleBooks!: (value: string[]) => void;
    booksApi.fetchBooks.mockImplementationOnce(() => new Promise((resolve) => (resolveStaleBooks = resolve)));
    act(() => {
      void client.refetchQueries({ queryKey: ["books"] });
    });

    navigation.pathname = "/login";
    rerender(
      <QueryProvider>
        <ClientProbe onClient={(c) => (client = c)} />
        <App />
      </QueryProvider>,
    );

    await loginAsB();
    navigation.pathname = "/library";
    booksApi.fetchBooks.mockResolvedValue(["Livro do Autor B"]);
    rerender(
      <QueryProvider>
        <ClientProbe onClient={(c) => (client = c)} />
        <App />
      </QueryProvider>,
    );
    expect(await screen.findByText("Livro do Autor B")).toBeInTheDocument();

    // A's stale fetch finally resolves — too late, and must never land in B's cache.
    act(() => resolveStaleBooks(["Livro obsoleto do Autor A"]));
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(client.getQueryData(["books"])).not.toEqual(["Livro obsoleto do Autor A"]);
    expect(screen.queryByText("Livro obsoleto do Autor A")).not.toBeInTheDocument();
  });

  test("mutation antiga de A em voo é purgada pela geração após o login B, não repopula o cache", async () => {
    const resolveRef: { current: (() => void) | null } = { current: null };
    const { rerender } = renderApp(<DelayedMutation resolveRef={resolveRef} />, (c) => (client = c));
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();
    await waitFor(() => expect(resolveRef.current).not.toBeNull());

    navigation.pathname = "/login";
    rerender(
      <QueryProvider>
        <ClientProbe onClient={(c) => (client = c)} />
        <App />
        <DelayedMutation resolveRef={resolveRef} />
      </QueryProvider>,
    );

    await loginAsB();
    navigation.pathname = "/library";
    rerender(
      <QueryProvider>
        <ClientProbe onClient={(c) => (client = c)} />
        <App />
      </QueryProvider>,
    );
    expect(await screen.findByText("Livro do Autor B")).toBeInTheDocument();

    // Only now does A's stale write land — after B's session has already replaced A's.
    act(() => resolveRef.current!());
    await new Promise((resolve) => setTimeout(resolve, 20));

    expect(client.getQueryData(["books"])).not.toEqual(["Rascunho não salvo do Autor A"]);
    expect(screen.queryByText("Rascunho não salvo do Autor A")).not.toBeInTheDocument();
  });
});
