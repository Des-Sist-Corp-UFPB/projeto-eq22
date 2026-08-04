import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import { useEffect } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { QueryProvider } from "@/components/providers/query-provider";
import { SessionGuard } from "@/features/auth/components/session-guard";

const SYNC_KEY = "iwrite-session-sync";

/**
 * Stands in for a genuinely separate tab's announceSessionChanged(): same channel/storage key, but
 * a token unrelated to this test process's own. Calling the real announceSessionChanged() here
 * would not do, precisely because it shares this module's TAB_ID with the tab under test — the
 * self-filtering that correctly stops a tab from reacting to its own login/logout would swallow it.
 */
function simulateOtherTabAnnouncement() {
  if (typeof BroadcastChannel !== "undefined") {
    const channel = new BroadcastChannel(SYNC_KEY);
    channel.postMessage("outra-aba");
    channel.close();
  } else {
    window.localStorage.setItem(SYNC_KEY, "outra-aba");
  }
}

/**
 * Covers thread #139-review-3's finding: cookies and the HttpSession are shared across same-origin
 * tabs, but each tab keeps its own QueryClient — so a login or logout in one tab left every other
 * tab showing the previous identity's cache until it happened to refetch something and hit a 401.
 * These tests drive a single rendered tab (the one under test) and simulate "another tab" the way
 * a real one would be observed from here: an opaque announceSessionChanged() broadcast (or, for the
 * fallback test, the `storage` event it degrades to) plus whatever /api/auth/me now answers -
 * exactly what a receiving tab has to work with. web/e2e/cross-tab-session-sync.e2e.ts drives two
 * real pages end to end for the same scenario.
 */

const authApi = vi.hoisted(() => ({ login: vi.fn(), fetchSession: vi.fn(), logout: vi.fn() }));
const booksApi = vi.hoisted(() => ({ fetchBooks: vi.fn() }));
const navigation = vi.hoisted(() => ({ replace: vi.fn(), pathname: "/library" }));

vi.mock("@/features/auth/api/auth-api", () => authApi);
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: navigation.replace }),
  usePathname: () => navigation.pathname,
}));

const sessionA = {
  user: { displayName: "Autor A", email: "autor-a@iwrite.local" },
  activeWorkspace: { name: "Espaço do Autor A", role: "OWNER" },
};
const sessionB = {
  user: { displayName: "Autor B", email: "autor-b@iwrite.local" },
  activeWorkspace: { name: "Espaço do Autor B", role: "OWNER" },
};

/** Stands in for the library screen: one query, keyed exactly like the real one, scoped server-side. */
function Library() {
  const { data } = useQuery({ queryKey: ["books"], queryFn: () => booksApi.fetchBooks() });
  return <ul>{(data ?? []).map((title) => <li key={title}>{title}</li>)}</ul>;
}

/** A write in flight when reconciliation lands - e.g. saving a scene - resolved by the test whenever
 *  it chooses, so the race ("does its result repopulate the cache after the swap?") is observable. */
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
    // Fired once, right as this tab renders - before any reconciliation has a chance to run.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return null;
}

function App({ extra }: { extra?: React.ReactNode }) {
  return (
    <SessionGuard>
      {navigation.pathname === "/login" ? <p>Formulário de login</p> : <Library />}
      {extra}
    </SessionGuard>
  );
}

function ClientProbe({ onClient }: { onClient: (client: QueryClient) => void }) {
  const client = useQueryClient();
  useEffect(() => {
    onClient(client);
  }, [client, onClient]);
  return null;
}

function renderTab(extra?: React.ReactNode) {
  let client!: QueryClient;
  const utils = render(
    <QueryProvider>
      <ClientProbe onClient={(c) => (client = c)} />
      <App extra={extra} />
    </QueryProvider>,
  );
  return { ...utils, getClient: () => client };
}

describe("sincronização de sessão entre abas", () => {
  let originalBroadcastChannel: typeof BroadcastChannel | undefined;

  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
    navigation.pathname = "/library";
    authApi.fetchSession.mockResolvedValue(sessionA);
    booksApi.fetchBooks.mockResolvedValue(["Livro do Autor A"]);
    originalBroadcastChannel = window.BroadcastChannel;
  });

  afterEach(() => {
    if (originalBroadcastChannel) {
      vi.stubGlobal("BroadcastChannel", originalBroadcastChannel);
    }
  });

  test("1. outra aba faz logout: este tab recebe o evento, perde os dados de A e volta ao login", async () => {
    const { getClient } = renderTab();
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();

    authApi.fetchSession.mockResolvedValue(null);
    act(() => simulateOtherTabAnnouncement());

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/login?reason=expired"));
    expect(getClient().getQueryData(["books"])).toBeUndefined();
    expect(getClient().getQueryData(["auth", "session"])).toBeNull();
    expect(screen.queryByText("Livro do Autor A")).not.toBeInTheDocument();
  });

  test("2. outra aba entra como B: o cache é limpo antes da revalidação, e só B aparece — nunca A transitoriamente", async () => {
    const { getClient } = renderTab();
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();

    let resolveFetchSession!: (value: typeof sessionB) => void;
    authApi.fetchSession.mockImplementationOnce(
      () => new Promise((resolve) => (resolveFetchSession = resolve)),
    );
    booksApi.fetchBooks.mockResolvedValue(["Livro do Autor B"]);

    act(() => simulateOtherTabAnnouncement());

    // Mid-flight: the broadcast is opaque, so the cache is purged before /api/auth/me even answers.
    await screen.findByText("Verificando sessão…");
    expect(getClient().getQueryData(["books"])).toBeUndefined();
    expect(screen.queryByText("Livro do Autor A")).not.toBeInTheDocument();

    resolveFetchSession(sessionB);

    await screen.findByText("Livro do Autor B");
    expect(screen.queryByText("Livro do Autor A")).not.toBeInTheDocument();
    expect(navigation.replace).not.toHaveBeenCalledWith(expect.stringContaining("/login"));
  });

  test("3. evento perdido: o foco revalida, /api/auth/me responde uma identidade diferente e B é carregado", async () => {
    const { getClient } = renderTab();
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();

    authApi.fetchSession.mockResolvedValue(sessionB);
    booksApi.fetchBooks.mockResolvedValue(["Livro do Autor B"]);

    act(() => window.dispatchEvent(new Event("focus")));

    await screen.findByText("Livro do Autor B");
    expect(screen.queryByText("Livro do Autor A")).not.toBeInTheDocument();
    expect(getClient().getQueryData(["auth", "session"])).toEqual(sessionB);
  });

  test("4. sessão não mudou: o foco revalida mas preserva o cache válido", async () => {
    const { getClient } = renderTab();
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();

    // fetchSession keeps resolving to the same Autor A — only the object identity changes, as a
    // real re-fetch would.
    authApi.fetchSession.mockResolvedValue({ ...sessionA });

    act(() => window.dispatchEvent(new Event("focus")));
    await screen.findByText("Verificando sessão…");
    await screen.findByText("Livro do Autor A");

    // Not refetched: the cached books entry from before the focus event is still the exact one
    // present after it, which fetchBooks (called only once) proves.
    expect(booksApi.fetchBooks).toHaveBeenCalledTimes(1);
    expect(getClient().getQueryData(["books"])).toEqual(["Livro do Autor A"]);
  });

  test("5. mutation atrasada de A não repopula o cache depois da troca para B", async () => {
    const resolveRef: { current: (() => void) | null } = { current: null };
    const { getClient } = renderTab(<DelayedMutation resolveRef={resolveRef} />);
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();
    await waitFor(() => expect(resolveRef.current).not.toBeNull());

    authApi.fetchSession.mockResolvedValue(sessionB);
    booksApi.fetchBooks.mockResolvedValue(["Livro do Autor B"]);
    act(() => simulateOtherTabAnnouncement());
    await screen.findByText("Livro do Autor B");

    // Only now does A's stale write land - after B's identity has already been accepted.
    act(() => resolveRef.current!());

    await waitFor(() => expect(getClient().getQueryData(["books"])).toEqual(["Livro do Autor B"]));
    expect(screen.queryByText("Rascunho não salvo do Autor A")).not.toBeInTheDocument();
  });

  test("6. 401 durante a reconciliação limpa o cache, redireciona ao login e não entra em loop", async () => {
    renderTab();
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();

    authApi.fetchSession.mockResolvedValue(null);
    act(() => simulateOtherTabAnnouncement());

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/login?reason=expired"));
    expect(navigation.replace).toHaveBeenCalledTimes(1);

    const callsAfterSettling = authApi.fetchSession.mock.calls.length;
    await new Promise((resolve) => setTimeout(resolve, 50));
    // No polling and no automatic retry: nothing re-invokes /api/auth/me on its own once the tab has
    // settled on "no session".
    expect(authApi.fetchSession.mock.calls.length).toBe(callsAfterSettling);
  });

  test("7. sem BroadcastChannel, o fallback por storage event ainda reconcilia a sessão", async () => {
    vi.stubGlobal("BroadcastChannel", undefined);

    renderTab();
    expect(await screen.findByText("Livro do Autor A")).toBeInTheDocument();

    authApi.fetchSession.mockResolvedValue(null);

    act(() => {
      // What a real other tab's announceSessionChanged() fallback does: write a new value under the
      // shared key. `storage` events never fire in the writing document, so this is dispatched by
      // hand to stand in for the event this tab would receive from a genuinely separate one - with a
      // foreign token, proving the listener does not merely react to any storage write.
      window.localStorage.setItem("iwrite-session-sync", "other-tab-id");
      window.dispatchEvent(
        new StorageEvent("storage", { key: "iwrite-session-sync", newValue: "other-tab-id" }),
      );
    });

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/login?reason=expired"));
    expect(screen.queryByText("Livro do Autor A")).not.toBeInTheDocument();
  });
});
