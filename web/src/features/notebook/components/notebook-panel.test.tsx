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
  createNotebookNote: vi.fn(),
  updateNotebookNote: vi.fn(),
  deleteNotebookNote: vi.fn(),
}));

vi.mock("@/features/notebook/api/notebook-api", () => ({
  listNotebookCategories: mocks.listNotebookCategories,
  listNotebookNotes: mocks.listNotebookNotes,
  createNotebookNote: mocks.createNotebookNote,
  updateNotebookNote: mocks.updateNotebookNote,
  deleteNotebookNote: mocks.deleteNotebookNote,
}));

describe("NotebookPanel", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.listNotebookCategories.mockResolvedValue([category]);
    mocks.listNotebookNotes.mockResolvedValue([]);
    mocks.createNotebookNote.mockResolvedValue(note);
    mocks.updateNotebookNote.mockResolvedValue(note);
    mocks.deleteNotebookNote.mockResolvedValue(undefined);
  });

  test("mostra estado vazio quando nao ha notas", async () => {
    renderWithClient(<NotebookPanel bookId="book-1" />);

    expect(await screen.findByRole("heading", { name: "Caderno" })).toBeInTheDocument();
    expect(await screen.findByText("Nenhuma nota no caderno")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Todas" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sem categoria" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Pesquisa" })).toBeInTheDocument();
  });

  test("cria nota e atualiza a lista", async () => {
    mocks.listNotebookNotes.mockResolvedValueOnce([]).mockResolvedValueOnce([note]);
    renderWithClient(<NotebookPanel bookId="book-1" />);

    fireEvent.click(await screen.findByRole("button", { name: "Nova nota" }));
    fireEvent.change(screen.getByLabelText("Título"), { target: { value: "Mapa de referencias" } });
    fireEvent.change(screen.getByLabelText("Conteúdo"), { target: { value: "Pesquisar mapas antigos." } });
    fireEvent.change(screen.getByLabelText("Categoria"), { target: { value: category.id } });
    fireEvent.click(screen.getByRole("button", { name: "Criar nota" }));

    await waitFor(() => {
      expect(mocks.createNotebookNote).toHaveBeenCalledWith("book-1", {
        title: "Mapa de referencias",
        content: "Pesquisar mapas antigos.",
        categoryId: category.id,
      });
    });
    await waitFor(() => {
      expect(mocks.listNotebookNotes).toHaveBeenCalledTimes(2);
    });
    expect(await screen.findByText("Nota criada com sucesso.")).toBeInTheDocument();
  });

  test("filtra notas pela categoria selecionada", async () => {
    mocks.listNotebookNotes.mockResolvedValueOnce([]).mockResolvedValueOnce([note]);
    renderWithClient(<NotebookPanel bookId="book-1" />);

    fireEvent.click(await screen.findByRole("button", { name: "Pesquisa" }));

    await waitFor(() => {
      expect(mocks.listNotebookNotes).toHaveBeenLastCalledWith("book-1", category.id);
    });
    expect(await screen.findByText("Mapa de referencias")).toBeInTheDocument();
  });
});
