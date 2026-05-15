import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { ExportManuscriptButton } from "@/features/export/components/export-manuscript-button";
import { renderWithClient } from "@/test/test-utils";

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

  test("mostra opcoes ao exportar", () => {
    renderWithClient(<ExportManuscriptButton bookId="book-1" />);

    expect(screen.getByRole("button", { name: "Exportar manuscrito" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Exportar manuscrito" }));

    expect(screen.getByLabelText(/Incluir titulos das cenas/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Incluir cenas vazias/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Baixar Markdown" })).toBeInTheDocument();
  });

  test("chama o endpoint de exportacao com query params selecionados", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response("conteudo", {
        status: 200,
        headers: { "content-disposition": 'attachment; filename="livro.md"' },
      })
    );

    renderWithClient(<ExportManuscriptButton bookId="book-1" />);

    fireEvent.click(screen.getByRole("button", { name: "Exportar manuscrito" }));
    fireEvent.click(screen.getByLabelText(/Incluir titulos das cenas/));
    fireEvent.click(screen.getByLabelText(/Incluir cenas vazias/));
    fireEvent.click(screen.getByRole("button", { name: "Baixar Markdown" }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        "http://localhost:8085/api/books/book-1/export?includeSceneTitles=true&includeEmptyScenes=true"
      );
    });
  });
});
