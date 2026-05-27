import { fireEvent, screen, waitFor } from "@testing-library/react";
import React from "react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { NotebookPanel } from "@/features/notebook/components/notebook-panel";
import type { NotebookCategory, NotebookNote } from "@/features/notebook/types";
import { renderWithClient } from "@/test/test-utils";

const category: NotebookCategory = {
  id: "category-1",
  bookId: "book-1",
  name: "Pesquisa",
  sortOrder: 1,
  isDefault: true,
  createdAt: "2026-05-20T10:00:00Z",
  updatedAt: "2026-05-20T10:00:00Z",
};

const note: NotebookNote = {
  id: "note-1",
  bookId: "book-1",
  categoryId: category.id,
  category,
  title: "Mapa de referencias",
  content: "Pesquisar mapas antigos.",
  createdAt: "2026-05-21T10:00:00Z",
  updatedAt: "2026-05-22T10:00:00Z",
};

const mocks = vi.hoisted(() => ({
  listNotebookCategories: vi.fn(),
  listNotebookNotes: vi.fn(),
  createNotebookCategory: vi.fn(),
  updateNotebookCategory: vi.fn(),
  deleteNotebookCategory: vi.fn(),
  createNotebookNote: vi.fn(),
  updateNotebookNote: vi.fn(),
  deleteNotebookNote: vi.fn(),
}));

vi.mock("@/features/notebook/api/notebook-api", () => ({
  listNotebookCategories: mocks.listNotebookCategories,
  listNotebookNotes: mocks.listNotebookNotes,
  createNotebookCategory: mocks.createNotebookCategory,
  updateNotebookCategory: mocks.updateNotebookCategory,
  deleteNotebookCategory: mocks.deleteNotebookCategory,
  createNotebookNote: mocks.createNotebookNote,
  updateNotebookNote: mocks.updateNotebookNote,
  deleteNotebookNote: mocks.deleteNotebookNote,
}));

describe("NotebookPanel category management", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.listNotebookCategories.mockResolvedValue([category]);
    mocks.listNotebookNotes.mockResolvedValue([]);
    mocks.createNotebookCategory.mockResolvedValue({ ...category, id: "category-2", name: "Mundo" });
    mocks.updateNotebookCategory.mockResolvedValue({ ...category, name: "Pesquisa revisada" });
    mocks.deleteNotebookCategory.mockResolvedValue(undefined);
    mocks.createNotebookNote.mockResolvedValue(note);
    mocks.updateNotebookNote.mockResolvedValue(note);
    mocks.deleteNotebookNote.mockResolvedValue(undefined);
  });

  test("cria categoria e atualiza categorias", async () => {
    mocks.listNotebookCategories.mockResolvedValueOnce([category]).mockResolvedValueOnce([
      category,
      { ...category, id: "category-2", name: "Mundo" },
    ]);
    renderWithClient(<NotebookPanel bookId="book-1" />);

    expect(await screen.findByRole("heading", { name: "Caderno" })).toBeInTheDocument();
    fireEvent.change(await screen.findByLabelText("Nova categoria"), { target: { value: "Mundo" } });
    fireEvent.click(screen.getByRole("button", { name: "Criar categoria" }));

    await waitFor(() => {
      expect(mocks.createNotebookCategory).toHaveBeenCalledWith("book-1", { name: "Mundo" });
    });
    await waitFor(() => {
      expect(mocks.listNotebookCategories).toHaveBeenCalledTimes(2);
    });
    expect(await screen.findByText("Categoria criada com sucesso.")).toBeInTheDocument();
  });

  test("renomeia categoria e atualiza categorias", async () => {
    mocks.listNotebookCategories
      .mockResolvedValueOnce([category])
      .mockResolvedValueOnce([{ ...category, name: "Pesquisa revisada" }]);
    renderWithClient(<NotebookPanel bookId="book-1" />);

    await screen.findByRole("heading", { name: "Caderno" });
    fireEvent.click(await screen.findByRole("button", { name: "Renomear" }));
    fireEvent.change(screen.getByLabelText("Renomear categoria"), { target: { value: "Pesquisa revisada" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar" }));

    await waitFor(() => {
      expect(mocks.updateNotebookCategory).toHaveBeenCalledWith(category.id, {
        name: "Pesquisa revisada",
      });
    });
    await waitFor(() => {
      expect(mocks.listNotebookCategories).toHaveBeenCalledTimes(2);
    });
    expect(await screen.findByText("Categoria atualizada com sucesso.")).toBeInTheDocument();
  });

  test("exclui categoria com confirmacao e atualiza categorias e notas", async () => {
    const uncategorizedNote = {
      ...note,
      categoryId: null,
      category: null,
    };
    mocks.listNotebookCategories.mockResolvedValueOnce([category]).mockResolvedValueOnce([]);
    mocks.listNotebookNotes.mockResolvedValueOnce([note]).mockResolvedValueOnce([uncategorizedNote]);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    renderWithClient(<NotebookPanel bookId="book-1" />);

    await screen.findByText("Mapa de referencias");
    fireEvent.click(screen.getAllByRole("button", { name: "Excluir" })[0]);

    expect(window.confirm).toHaveBeenCalledWith(
      'Excluir a categoria "Pesquisa"? As notas desta categoria continuarão no caderno sem categoria.'
    );
    await waitFor(() => {
      expect(mocks.deleteNotebookCategory).toHaveBeenCalledWith(category.id);
    });
    await waitFor(() => {
      expect(mocks.listNotebookCategories).toHaveBeenCalledTimes(2);
    });
    await waitFor(() => {
      expect(mocks.listNotebookNotes).toHaveBeenCalledTimes(2);
    });
    fireEvent.click(screen.getByRole("button", { name: "Sem categoria" }));
    expect(await screen.findByText("Mapa de referencias")).toBeInTheDocument();
  });

  test("filtro de notas por categoria continua chamando a API com categoria selecionada", async () => {
    mocks.listNotebookNotes.mockResolvedValueOnce([]).mockResolvedValueOnce([note]);
    renderWithClient(<NotebookPanel bookId="book-1" />);

    fireEvent.click(await screen.findByRole("button", { name: /Pesquisa/ }));

    await waitFor(() => {
      expect(mocks.listNotebookNotes).toHaveBeenLastCalledWith("book-1", category.id);
    });
    expect(await screen.findByText("Mapa de referencias")).toBeInTheDocument();
  });
});
