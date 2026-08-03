import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { QueryProvider } from "@/components/providers/query-provider";
import { ExportManuscriptButton } from "@/features/export/components/export-manuscript-button";
import { SessionGuard } from "@/features/auth/components/session-guard";
import { renderWithClient } from "@/test/test-utils";

const authApi = vi.hoisted(() => ({ fetchSession: vi.fn(), login: vi.fn(), logout: vi.fn() }));
const navigation = vi.hoisted(() => ({ replace: vi.fn(), pathname: "/library" }));

vi.mock("@/features/auth/api/auth-api", () => authApi);
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: navigation.replace }),
  usePathname: () => navigation.pathname,
}));

describe("ExportManuscriptButton", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    Object.defineProperty(window.URL, "createObjectURL", {
      configurable: true,
      value: vi.fn(() => "blob:export"),
    });
    Object.defineProperty(window.URL, "revokeObjectURL", {
      configurable: true,
      value: vi.fn(),
    });
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
  });

  test("mostra formatos TXT Markdown e DOCX com Markdown selecionado por padrao", () => {
    renderWithClient(<ExportManuscriptButton bookId="book-1" />);

    expect(screen.getByRole("button", { name: "Exportar manuscrito" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Exportar manuscrito" }));

    expect(screen.getByLabelText("TXT (.txt)")).not.toBeChecked();
    expect(screen.getByLabelText("Markdown (.md)")).toBeChecked();
    expect(screen.getByLabelText("Word (.docx)")).not.toBeChecked();
    expect(screen.getByLabelText(/Incluir titulos das cenas/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Incluir cenas vazias/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Baixar manuscrito" })).toBeInTheDocument();
  });

  test("Markdown padrao chama o endpoint canonico com query params selecionados", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response("conteudo markdown", {
        status: 200,
        headers: { "content-disposition": 'attachment; filename="livro.md"' },
      })
    );

    renderWithClient(<ExportManuscriptButton bookId="book-1" />);

    fireEvent.click(screen.getByRole("button", { name: "Exportar manuscrito" }));
    fireEvent.click(screen.getByLabelText(/Incluir titulos das cenas/));
    fireEvent.click(screen.getByLabelText(/Incluir cenas vazias/));
    fireEvent.click(screen.getByRole("button", { name: "Baixar manuscrito" }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        "/api/books/book-1/exports/manuscript?format=md&includeSceneTitles=true&includeEmptyScenes=true",
        { credentials: "same-origin" }
      );
    });
  });

  test("selecionar DOCX chama o endpoint canonico com query params selecionados", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response("conteudo docx", {
        status: 200,
        headers: { "content-disposition": 'attachment; filename="livro.docx"' },
      })
    );

    renderWithClient(<ExportManuscriptButton bookId="book-1" />);

    fireEvent.click(screen.getByRole("button", { name: "Exportar manuscrito" }));
    fireEvent.click(screen.getByLabelText("Word (.docx)"));
    fireEvent.click(screen.getByLabelText(/Incluir titulos das cenas/));
    fireEvent.click(screen.getByLabelText(/Incluir cenas vazias/));
    fireEvent.click(screen.getByRole("button", { name: "Baixar manuscrito" }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        "/api/books/book-1/exports/manuscript?format=docx&includeSceneTitles=true&includeEmptyScenes=true",
        { credentials: "same-origin" }
      );
    });
  });

  test("selecionar TXT chama o endpoint canonico com formato txt", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response("conteudo txt", {
        status: 200,
        headers: { "content-disposition": 'attachment; filename="livro.txt"' },
      })
    );

    renderWithClient(<ExportManuscriptButton bookId="book-1" />);

    fireEvent.click(screen.getByRole("button", { name: "Exportar manuscrito" }));
    fireEvent.click(screen.getByLabelText("TXT (.txt)"));
    fireEvent.click(screen.getByRole("button", { name: "Baixar manuscrito" }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        "/api/books/book-1/exports/manuscript?format=txt&includeSceneTitles=false&includeEmptyScenes=false",
        { credentials: "same-origin" }
      );
    });
  });

  test("exportacao pendente desabilita novo submit e erro mostra feedback claro", async () => {
    let rejectDownload: (error: Error) => void = () => undefined;
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(
      () =>
        new Promise<Response>((_resolve, reject) => {
          rejectDownload = reject;
        })
    );

    renderWithClient(<ExportManuscriptButton bookId="book-1" />);

    fireEvent.click(screen.getByRole("button", { name: "Exportar manuscrito" }));
    const submitButton = screen.getByRole("button", { name: "Baixar manuscrito" });
    fireEvent.click(submitButton);

    expect(await screen.findByRole("button", { name: "Exportando..." })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Exportando..." }));
    expect(fetchMock).toHaveBeenCalledTimes(1);

    rejectDownload(new Error("falhou"));

    expect(await screen.findByText("Nao foi possivel exportar o manuscrito agora. Tente novamente.")).toBeInTheDocument();
  });

  test("exportacao que recebe 401 encerra a sessao e leva ao login, sem mensagem de falha de exportacao", async () => {
    navigation.pathname = "/library";
    authApi.fetchSession.mockResolvedValue({
      user: { displayName: "Autor A", email: "autor-a@iwrite.local" },
      activeWorkspace: { name: "Espaço do Autor A", role: "OWNER" },
    });
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ messages: ["Sua sessão expirou."] }), { status: 401 })
    );

    // The real provider, because the 401 handling that closes the session lives on its caches.
    render(
      <QueryProvider>
        <SessionGuard>
          <ExportManuscriptButton bookId="book-1" />
        </SessionGuard>
      </QueryProvider>
    );

    fireEvent.click(await screen.findByRole("button", { name: "Exportar manuscrito" }));
    fireEvent.click(screen.getByRole("button", { name: "Baixar manuscrito" }));

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/login?reason=expired"));
    expect(screen.queryByText("Nao foi possivel exportar o manuscrito agora. Tente novamente.")).not.toBeInTheDocument();
  });
});
