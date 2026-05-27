import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import React from "react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { NotebookPanel } from "@/features/notebook/components/notebook-panel";
import type { NotebookCategory, NotebookNote } from "@/features/notebook/types";
import { renderWithClient } from "@/test/test-utils";

const pesquisaCategory = categoryFixture("category-pesquisa", "Pesquisa", true, 1);
const ideiaCategory = categoryFixture("category-ideia", "Ideia", true, 0);
const customCategory = categoryFixture("category-custom", "Cronologia", false, 2);
const outroCategory = categoryFixture("category-outro", "Outro", true, 99);

const pesquisaNote = noteFixture({
  id: "note-pesquisa",
  title: "Mapa de referencias",
  category: pesquisaCategory,
});

const customNote = noteFixture({
  id: "note-custom",
  title: "Linha alternativa",
  category: customCategory,
});

const uncategorizedNote = noteFixture({
  id: "note-sem-categoria",
  title: "Nota solta",
  category: null,
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

describe("NotebookPanel layout and category management", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.listNotebookCategories.mockResolvedValue([customCategory, outroCategory, pesquisaCategory, ideiaCategory]);
    mocks.listNotebookNotes.mockResolvedValue([pesquisaNote, customNote, uncategorizedNote]);
    mocks.createNotebookCategory.mockResolvedValue(categoryFixture("category-mundo", "Mundo", false, 3));
    mocks.updateNotebookCategory.mockResolvedValue({ ...pesquisaCategory, name: "Pesquisa revisada" });
    mocks.deleteNotebookCategory.mockResolvedValue(undefined);
    mocks.createNotebookNote.mockResolvedValue(customNote);
    mocks.updateNotebookNote.mockResolvedValue(customNote);
    mocks.deleteNotebookNote.mockResolvedValue(undefined);
  });

  test("criar nota com outro filtro ativo deixa a nova nota visivel e aberta", async () => {
    mocks.listNotebookNotes.mockImplementation((_bookId: string, categoryId?: string | null) => {
      if (categoryId === pesquisaCategory.id) {
        return Promise.resolve([pesquisaNote]);
      }
      if (categoryId === customCategory.id) {
        return Promise.resolve([]);
      }
      return Promise.resolve([pesquisaNote, uncategorizedNote]);
    });
    renderWithClient(<NotebookPanel bookId="book-1" />);

    fireEvent.click(await screen.findByRole("button", { name: "Pesquisa" }));
    fireEvent.click(screen.getByRole("button", { name: "Nova nota" }));
    fireEvent.change(screen.getByLabelText("Título"), { target: { value: customNote.title } });
    fireEvent.change(screen.getByLabelText("Categoria"), { target: { value: customCategory.id } });
    fireEvent.click(screen.getByRole("button", { name: "Criar nota" }));

    await waitFor(() => {
      expect(mocks.createNotebookNote).toHaveBeenCalledWith("book-1", {
        title: customNote.title,
        content: null,
        categoryId: customCategory.id,
      });
    });
    expect(await screen.findByText("Nota criada com sucesso.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cronologia" })).toHaveClass("bg-zinc-900");
    expect(screen.getAllByText(customNote.title).length).toBeGreaterThan(0);
    expect(screen.getByRole("heading", { name: "Editar nota" })).toBeInTheDocument();
  });

  test("Outro aparece por ultimo nos filtros e no seletor de categoria", async () => {
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

  test("gerenciador de categorias cria renomeia e exclui categoria", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    renderWithClient(<NotebookPanel bookId="book-1" />);

    fireEvent.click(await screen.findByRole("button", { name: "Gerenciar categorias" }));
    const dialog = await screen.findByRole("dialog");

    fireEvent.change(within(dialog).getByLabelText("Nova categoria"), { target: { value: "Mundo" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Criar categoria" }));
    await waitFor(() => {
      expect(mocks.createNotebookCategory).toHaveBeenCalledWith("book-1", { name: "Mundo" });
    });

    fireEvent.click(within(dialog).getAllByRole("button", { name: "Renomear" })[0]);
    fireEvent.change(within(dialog).getByLabelText("Renomear categoria"), {
      target: { value: "Pesquisa revisada" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Salvar" }));
    await waitFor(() => {
      expect(mocks.updateNotebookCategory).toHaveBeenCalledWith(expect.any(String), { name: "Pesquisa revisada" });
    });

    fireEvent.click(screen.getByRole("button", { name: "Gerenciar categorias" }));
    const refreshedDialog = await screen.findByRole("dialog");
    fireEvent.click(within(refreshedDialog).getAllByRole("button", { name: "Excluir" })[0]);
    await waitFor(() => {
      expect(mocks.deleteNotebookCategory).toHaveBeenCalledWith(expect.any(String));
    });
    expect(mocks.listNotebookCategories).toHaveBeenCalled();
    expect(mocks.listNotebookNotes).toHaveBeenCalled();
  });

  test("lista de notas e editor aparecem juntos no novo layout", async () => {
    renderWithClient(<NotebookPanel bookId="book-1" />);

    const notesList = await screen.findByLabelText("Notas do caderno");
    expect(within(notesList).getByText(pesquisaNote.title)).toBeInTheDocument();

    fireEvent.click(within(notesList).getByText(pesquisaNote.title));

    expect(await screen.findByRole("heading", { name: "Editar nota" })).toBeInTheDocument();
    expect(screen.getByDisplayValue(pesquisaNote.title)).toBeInTheDocument();
    expect(screen.getByLabelText("Notas do caderno")).toBeInTheDocument();
  });

  test("filtro por categoria normal continua chamando a API com a categoria selecionada", async () => {
    mocks.listNotebookNotes.mockResolvedValueOnce([pesquisaNote, customNote]).mockResolvedValueOnce([pesquisaNote]);
    renderWithClient(<NotebookPanel bookId="book-1" />);

    fireEvent.click(await screen.findByRole("button", { name: "Pesquisa" }));

    await waitFor(() => {
      expect(mocks.listNotebookNotes).toHaveBeenLastCalledWith("book-1", pesquisaCategory.id);
    });
    expect(await screen.findByText(pesquisaNote.title)).toBeInTheDocument();
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
}: {
  id: string;
  title: string;
  category: NotebookCategory | null;
}): NotebookNote {
  return {
    id,
    bookId: "book-1",
    categoryId: category?.id ?? null,
    category,
    title,
    content: "Pesquisar mapas antigos.",
    createdAt: "2026-05-21T10:00:00Z",
    updatedAt: "2026-05-22T10:00:00Z",
  };
}
