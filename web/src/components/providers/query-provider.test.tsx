import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useEffect, type ReactElement } from "react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { QueryProvider } from "@/components/providers/query-provider";
import { LoginForm } from "@/features/auth/components/login-form";
import { SessionGuard } from "@/features/auth/components/session-guard";
import { ApiError } from "@/lib/api/client";

const authApi = vi.hoisted(() => ({ login: vi.fn(), fetchSession: vi.fn(), logout: vi.fn() }));
const booksApi = vi.hoisted(() => ({ fetchBooks: vi.fn() }));
const navigation = vi.hoisted(() => ({ replace: vi.fn(), pathname: "/library" }));

vi.mock("@/features/auth/api/auth-api", () => authApi);
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: navigation.replace }),
  usePathname: () => navigation.pathname,
}));

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
});
