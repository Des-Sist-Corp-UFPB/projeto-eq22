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

  test("mostra o botao de exportacao", () => {
    renderWithClient(<ExportManuscriptButton bookId="book-1" />);

    expect(screen.getByRole("button", { name: "Exportar manuscrito" })).toBeInTheDocument();
  });

  test("chama o endpoint de exportacao e mostra erro amigavel quando falha", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(
        new Response("conteudo", {
          status: 200,
          headers: { "content-disposition": 'attachment; filename="livro.md"' },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ messages: ["Book not found"] }), {
          status: 404,
          headers: { "content-type": "application/json" },
        })
      );

    renderWithClient(<ExportManuscriptButton bookId="book-1" />);

    fireEvent.click(screen.getByRole("button", { name: "Exportar manuscrito" }));
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith("http://localhost:8085/api/books/book-1/export");
    });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Exportar manuscrito" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Nao foi possivel exportar o manuscrito agora. Tente novamente.");
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
