import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import React from "react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { NotebookPanel } from "@/features/notebook/components/notebook-panel";
import type { NotebookCategory, NotebookNote, NotebookNoteStatus } from "@/features/notebook/types";
import { renderWithClient } from "@/test/test-utils";

const pesquisaCategory = categoryFixture("category-pesquisa", "Pesquisa", true, 1);
const ideiaCategory = categoryFixture("category-ideia", "Ideia", true, 0);
const customCategory = categoryFixture("category-custom", "Cronologia", false, 2);
const outroCategory = categoryFixture("category-outro", "Outro", true, 99);

const pesquisaNote = noteFixture({
  id: "note-pesquisa",
  title: "Mapa de referencias",
  category: pesquisaCategory,
  status: "OPEN",
});

const resolvedNote = noteFixture({
  id: "note-resolved",
  title: "Pergunta respondida",
  category: customCategory,
  status: "RESOLVED",
});

const customNote = noteFixture({
  id: "note-custom",
  title: "Linha alternativa",
  category: customCategory,
  status: "OPEN",
});

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

describe("NotebookPanel writing layout and status", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.listNotebookCategories.mockResolvedValue([customCategory, outroCategory, pesquisaCategory, ideiaCategory]);
    mocks.listNotebookNotes.mockResolvedValue([pesquisaNote, resolvedNote]);
    mocks.createNotebookCategory.mockResolvedValue(categoryFixture("category-mundo", "Mundo", false, 3));
    mocks.updateNotebookCategory.mockResolvedValue({ ...pesquisaCategory, name: "Pesquisa revisada" });
    mocks.deleteNotebookCategory.mockResolvedValue(undefined);
    mocks.createNotebookNote.mockResolvedValue(customNote);
    mocks.updateNotebookNote.mockResolvedValue({ ...pesquisaNote, status: "RESOLVED" });
    mocks.deleteNotebookNote.mockResolvedValue(undefined);
  });

  test("filtro de status mostra opcoes OPEN e RESOLVED", async () => {
    renderWithClient(<NotebookPanel bookId="book-1" />);

    const statusFilters = within(await screen.findByLabelText("Filtros de status")).getAllByRole("button");

    expect(statusFilters.map((button) => button.textContent)).toEqual(["Todas", "Abertas", "Resolvidas"]);
  });

  test("criar nota abre a nota e a mantem visivel", async () => {
    mocks.listNotebookNotes.mockImplementation((_bookId: string, categoryId?: string | null) => {
      if (categoryId === pesquisaCategory.id) {
        return Promise.resolve([pesquisaNote]);
      }
      if (categoryId === customCategory.id) {
        return Promise.resolve([]);
      }
      return Promise.resolve([pesquisaNote]);
    });
    renderWithClient(<NotebookPanel bookId="book-1" />);

    fireEvent.click(await screen.findByRole("button", { name: "Resolvidas" }));
    fireEvent.change(screen.getByLabelText("Buscar notas"), { target: { value: "nao aparece" } });
    fireEvent.click(screen.getByRole("button", { name: "Nova nota" }));
    fireEvent.change(screen.getByLabelText("Título"), { target: { value: customNote.title } });
    fireEvent.change(screen.getByLabelText("Categoria"), { target: { value: customCategory.id } });
    fireEvent.click(screen.getByRole("button", { name: "Criar nota" }));

    await waitFor(() => {
      expect(mocks.createNotebookNote).toHaveBeenCalledWith("book-1", {
        title: customNote.title,
        content: null,
        categoryId: customCategory.id,
        status: "OPEN",
      });
    });
    expect(await screen.findByText("Nota criada com sucesso.")).toBeInTheDocument();
    expect(screen.getAllByText(customNote.title).length).toBeGreaterThan(0);
    expect(screen.getByRole("heading", { name: "Editar nota" })).toBeInTheDocument();
    expect(screen.getByDisplayValue(customNote.title)).toBeInTheDocument();
  });

  test("atualizar status da nota chama API e atualiza notas", async () => {
    renderWithClient(<NotebookPanel bookId="book-1" />);

    const notesList = await screen.findByLabelText("Notas do caderno");
    fireEvent.click(within(notesList).getByText(pesquisaNote.title));
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "RESOLVED" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar nota" }));

    await waitFor(() => {
      expect(mocks.updateNotebookNote).toHaveBeenCalledWith(pesquisaNote.id, {
        title: pesquisaNote.title,
        content: pesquisaNote.content,
        categoryId: pesquisaCategory.id,
        status: "RESOLVED",
      });
    });
    expect(mocks.listNotebookNotes).toHaveBeenCalled();
  });

  test("filtro por categoria continua funcionando com Outro por ultimo", async () => {
    mocks.listNotebookNotes.mockResolvedValueOnce([pesquisaNote, resolvedNote]).mockResolvedValueOnce([pesquisaNote]);
    renderWithClient(<NotebookPanel bookId="book-1" />);

    const filters = within(await screen.findByLabelText("Filtros de categoria")).getAllByRole("button");
    expect(filters.map((button) => button.textContent)).toEqual([
      "Todas",
      "Sem categoria",
      "Ideia",
      "Pesquisa",
      "Cronologia",
      "Outro",
    ]);

    fireEvent.click(screen.getByRole("button", { name: "Pesquisa" }));
    await waitFor(() => {
      expect(mocks.listNotebookNotes).toHaveBeenLastCalledWith("book-1", pesquisaCategory.id);
    });

    fireEvent.click(screen.getByRole("button", { name: "Nova nota" }));
    const options = within(screen.getByLabelText("Categoria")).getAllByRole("option");
    expect(options.map((option) => option.textContent)).toEqual([
      "Sem categoria",
      "Ideia",
      "Pesquisa",
      "Cronologia",
      "Outro",
    ]);
  });

  test("novo layout renderiza filtros lista e editor em duas areas", async () => {
    renderWithClient(<NotebookPanel bookId="book-1" />);

    expect(await screen.findByLabelText("Navegação do caderno")).toBeInTheDocument();
    expect(screen.getByLabelText("Buscar notas")).toBeInTheDocument();
    const notesList = screen.getByLabelText("Notas do caderno");
    expect(within(notesList).getByText(pesquisaNote.title)).toBeInTheDocument();

    fireEvent.click(within(notesList).getByText(pesquisaNote.title));

    expect(screen.getByLabelText("Editor do caderno")).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Editar nota" })).toBeInTheDocument();
    expect(screen.getByDisplayValue(pesquisaNote.title)).toBeInTheDocument();
  });
});

function categoryFixture(id: string, name: string, isDefault: boolean, sortOrder: number): NotebookCategory {
  return {
    id,
    bookId: "book-1",
    name,
    sortOrder,
    isDefault,
    createdAt: "2026-05-20T10:00:00Z",
    updatedAt: "2026-05-20T10:00:00Z",
  };
}

function noteFixture({
  id,
  title,
  category,
  status,
}: {
  id: string;
  title: string;
  category: NotebookCategory | null;
  status: NotebookNoteStatus;
}): NotebookNote {
  return {
    id,
    bookId: "book-1",
    categoryId: category?.id ?? null,
    category,
    title,
    content: "Pesquisar mapas antigos.",
    status,
    createdAt: "2026-05-21T10:00:00Z",
    updatedAt: "2026-05-22T10:00:00Z",
  };
}
