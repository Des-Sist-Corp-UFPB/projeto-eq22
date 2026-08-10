import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import React from "react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { NotebookPanel } from "@/features/notebook/components/notebook-panel";
import type { NotebookCategory, NotebookNote, NotebookNoteStatus } from "@/features/notebook/types";
import { renderWithClient } from "@/test/test-utils";

const pesquisaCategory = categoryFixture("category-pesquisa", "Pesquisa", 1);
const ideiaCategory = categoryFixture("category-ideia", "Ideia", 0);
const customCategory = categoryFixture("category-custom", "Cronologia", 2);
const outroCategory = categoryFixture("category-outro", "Outro", 99);

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

const exportMocks = vi.hoisted(() => ({
  downloadNotebookExport: vi.fn(),
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

vi.mock("@/features/export/api/export-api", () => ({
  downloadNotebookExport: exportMocks.downloadNotebookExport,
}));

describe("NotebookPanel writing layout and status", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    exportMocks.downloadNotebookExport.mockResolvedValue(undefined);
    mocks.listNotebookCategories.mockResolvedValue([customCategory, outroCategory, pesquisaCategory, ideiaCategory]);
    mocks.listNotebookNotes.mockResolvedValue([pesquisaNote, resolvedNote]);
    mocks.createNotebookCategory.mockResolvedValue(categoryFixture("category-mundo", "Mundo", 3));
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

  test("categorias iniciais exibem acoes de renomear e excluir sem badge Padrao", async () => {
    renderWithClient(<NotebookPanel bookId="book-1" />);

    fireEvent.click(await screen.findByRole("button", { name: "Gerenciar categorias" }));
    const defaultCategoryRow = screen.getByRole("group", { name: "Categoria Pesquisa" });

    expect(within(defaultCategoryRow).getByRole("button", { name: "Renomear" })).toBeInTheDocument();
    expect(within(defaultCategoryRow).getByRole("button", { name: "Excluir" })).toBeInTheDocument();
    expect(within(defaultCategoryRow).queryByText("Padrão")).not.toBeInTheDocument();
  });

  test("categorias customizadas continuam exibindo acoes de renomear e excluir", async () => {
    renderWithClient(<NotebookPanel bookId="book-1" />);

    fireEvent.click(await screen.findByRole("button", { name: "Gerenciar categorias" }));
    const customCategoryRow = screen.getByRole("group", { name: "Categoria Cronologia" });

    expect(within(customCategoryRow).getByRole("button", { name: "Renomear" })).toBeInTheDocument();
    expect(within(customCategoryRow).getByRole("button", { name: "Excluir" })).toBeInTheDocument();
  });

  test("renomear categoria atualiza categorias e notas", async () => {
    const renamedCategory = { ...customCategory, name: "Cronologia revisada" };
    mocks.updateNotebookCategory.mockResolvedValue(renamedCategory);
    mocks.listNotebookNotes
      .mockResolvedValueOnce([pesquisaNote, resolvedNote])
      .mockResolvedValueOnce([pesquisaNote, { ...resolvedNote, category: renamedCategory }]);
    renderWithClient(<NotebookPanel bookId="book-1" />);

    await screen.findByLabelText("Notas do caderno");
    expect(mocks.listNotebookCategories).toHaveBeenCalledTimes(1);
    expect(mocks.listNotebookNotes).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole("button", { name: "Gerenciar categorias" }));
    const customCategoryRow = screen.getByRole("group", { name: "Categoria Cronologia" });
    fireEvent.click(within(customCategoryRow).getByRole("button", { name: "Renomear" }));
    fireEvent.change(within(customCategoryRow).getByLabelText("Renomear categoria"), {
      target: { value: "Cronologia revisada" },
    });
    fireEvent.click(within(customCategoryRow).getByRole("button", { name: "Salvar" }));

    await waitFor(() => {
      expect(mocks.updateNotebookCategory).toHaveBeenCalledWith(customCategory.id, { name: "Cronologia revisada" });
    });
    await waitFor(() => {
      expect(mocks.listNotebookCategories).toHaveBeenCalledTimes(2);
      expect(mocks.listNotebookNotes).toHaveBeenCalledTimes(2);
    });
    expect(await screen.findByText("Cronologia revisada")).toBeInTheDocument();
  });

  test("excluir categoria confirma movimento para Sem categoria e preserva conteudo do editor", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(true);
    renderWithClient(<NotebookPanel bookId="book-1" />);

    const notesList = await screen.findByLabelText("Notas do caderno");
    fireEvent.click(within(notesList).getByText(resolvedNote.title));
    expect(screen.getByLabelText("Categoria")).toHaveValue(customCategory.id);
    fireEvent.change(screen.getByLabelText("Conteúdo"), { target: { value: "Rascunho ainda nao salvo" } });

    fireEvent.click(screen.getByRole("button", { name: "Gerenciar categorias" }));
    const customCategoryRow = screen.getByRole("group", { name: "Categoria Cronologia" });
    fireEvent.click(within(customCategoryRow).getByRole("button", { name: "Excluir" }));

    expect(confirmSpy).toHaveBeenCalledWith(
      'Excluir a categoria "Cronologia"? As notas desta categoria serão movidas para "Sem categoria".'
    );
    await waitFor(() => {
      expect(mocks.deleteNotebookCategory).toHaveBeenCalledWith(customCategory.id);
    });
    expect(screen.getByLabelText("Categoria")).toHaveValue("");
    expect(screen.getByDisplayValue(resolvedNote.title)).toBeInTheDocument();
    expect(screen.getByDisplayValue("Rascunho ainda nao salvo")).toBeInTheDocument();
    expect(screen.getByLabelText("Status")).toHaveValue(resolvedNote.status);
  });

  test("acao de exportar caderno abre opcoes com notas abertas e resolvidas por padrao", async () => {
    renderWithClient(<NotebookPanel bookId="book-1" />);

    fireEvent.click(await screen.findByRole("button", { name: "Exportar caderno" }));

    expect(screen.getByText("A exportação inclui todas as categorias do Caderno.")).toBeInTheDocument();
    expect(screen.getByLabelText("TXT (.txt)")).toBeChecked();
    expect(screen.getByLabelText("Markdown (.md)")).not.toBeChecked();
    expect(screen.getByLabelText("Word (.docx)")).not.toBeChecked();
    expect(screen.getByLabelText(/Incluir notas abertas/)).toBeChecked();
    expect(screen.getByLabelText(/Incluir notas resolvidas/)).toBeChecked();
  });

  test("exportacao do caderno envia formato e opcoes selecionadas", async () => {
    renderWithClient(<NotebookPanel bookId="book-1" />);

    fireEvent.click(await screen.findByRole("button", { name: "Exportar caderno" }));
    fireEvent.click(screen.getByLabelText("Word (.docx)"));
    fireEvent.click(screen.getByLabelText(/Incluir notas resolvidas/));
    fireEvent.click(screen.getByRole("button", { name: "Baixar caderno" }));

    await waitFor(() => {
      expect(exportMocks.downloadNotebookExport).toHaveBeenCalledWith("book-1", {
        format: "docx",
        includeOpen: true,
        includeResolved: false,
      });
    });
  });

  test("exportacao do caderno ignora filtros atuais de categoria status e busca", async () => {
    renderWithClient(<NotebookPanel bookId="book-1" />);

    fireEvent.click(await screen.findByRole("button", { name: "Pesquisa" }));
    fireEvent.click(screen.getByRole("button", { name: "Abertas" }));
    fireEvent.change(screen.getByLabelText("Buscar notas"), { target: { value: "mapa" } });
    fireEvent.click(screen.getByRole("button", { name: "Exportar caderno" }));
    fireEvent.click(screen.getByLabelText("Markdown (.md)"));
    fireEvent.click(screen.getByRole("button", { name: "Baixar caderno" }));

    await waitFor(() => {
      expect(exportMocks.downloadNotebookExport).toHaveBeenCalledWith("book-1", {
        format: "md",
        includeOpen: true,
        includeResolved: true,
      });
    });
    expect(exportMocks.downloadNotebookExport).not.toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({
        selectedCategoryId: expect.anything(),
        selectedStatus: expect.anything(),
        searchTerm: expect.anything(),
      })
    );
  });

  test("exportacao do caderno valida selecao vazia de status e nao faz request", async () => {
    renderWithClient(<NotebookPanel bookId="book-1" />);

    fireEvent.click(await screen.findByRole("button", { name: "Exportar caderno" }));
    fireEvent.click(screen.getByLabelText(/Incluir notas abertas/));
    fireEvent.click(screen.getByLabelText(/Incluir notas resolvidas/));
    fireEvent.click(screen.getByRole("button", { name: "Baixar caderno" }));

    expect(screen.getByText("Selecione pelo menos um tipo de nota para exportar.")).toBeInTheDocument();
    expect(exportMocks.downloadNotebookExport).not.toHaveBeenCalled();
  });

  test("exportacao pendente desabilita submit duplicado e falha mostra feedback", async () => {
    let rejectExport: (error: Error) => void = () => undefined;
    exportMocks.downloadNotebookExport.mockImplementation(
      () =>
        new Promise<void>((_resolve, reject) => {
          rejectExport = reject;
        })
    );
    renderWithClient(<NotebookPanel bookId="book-1" />);

    fireEvent.click(await screen.findByRole("button", { name: "Exportar caderno" }));
    fireEvent.click(screen.getByRole("button", { name: "Baixar caderno" }));

    expect(await screen.findByRole("button", { name: "Exportando..." })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Exportando..." }));
    expect(exportMocks.downloadNotebookExport).toHaveBeenCalledTimes(1);

    rejectExport(new Error("falhou"));

    expect(await screen.findByText("Nao foi possivel exportar o caderno agora. Tente novamente.")).toBeInTheDocument();
  });

  test("exportacao nao altera filtros nem nota aberta", async () => {
    renderWithClient(<NotebookPanel bookId="book-1" />);

    fireEvent.click(await screen.findByRole("button", { name: "Pesquisa" }));
    fireEvent.click(screen.getByRole("button", { name: "Abertas" }));
    fireEvent.change(screen.getByLabelText("Buscar notas"), { target: { value: "mapa" } });
    const notesList = await screen.findByLabelText("Notas do caderno");
    fireEvent.click(within(notesList).getByText(pesquisaNote.title));
    fireEvent.change(await screen.findByLabelText("Conteúdo"), { target: { value: "Rascunho preservado" } });

    fireEvent.click(screen.getByRole("button", { name: "Exportar caderno" }));
    fireEvent.click(screen.getByRole("button", { name: "Baixar caderno" }));

    await waitFor(() => {
      expect(exportMocks.downloadNotebookExport).toHaveBeenCalledTimes(1);
    });
    expect(screen.getByRole("button", { name: "Pesquisa" })).toBeInTheDocument();
    expect(screen.getByLabelText("Buscar notas")).toHaveValue("mapa");
    expect(screen.getByRole("heading", { name: "Editar nota" })).toBeInTheDocument();
    expect(screen.getByDisplayValue("Rascunho preservado")).toBeInTheDocument();
  });

  test("excluir categoria usada como filtro ativo muda para Sem categoria", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    mocks.listNotebookNotes.mockResolvedValue([resolvedNote, { ...pesquisaNote, category: null, categoryId: null }]);
    renderWithClient(<NotebookPanel bookId="book-1" />);

    fireEvent.click(await screen.findByRole("button", { name: "Cronologia" }));
    await waitFor(() => {
      expect(mocks.listNotebookNotes).toHaveBeenLastCalledWith("book-1", customCategory.id);
    });

    fireEvent.click(screen.getByRole("button", { name: "Gerenciar categorias" }));
    const customCategoryRow = screen.getByRole("group", { name: "Categoria Cronologia" });
    fireEvent.click(within(customCategoryRow).getByRole("button", { name: "Excluir" }));

    await waitFor(() => {
      expect(mocks.deleteNotebookCategory).toHaveBeenCalledWith(customCategory.id);
    });
    expect(screen.getByText("Mapa de referencias")).toBeInTheDocument();
  });

  test("clicar no filtro ativo nao refaz busca nem limpa editor", async () => {
    renderWithClient(<NotebookPanel bookId="book-1" />);

    const notesList = await screen.findByLabelText("Notas do caderno");
    fireEvent.click(within(notesList).getByText(pesquisaNote.title));
    fireEvent.change(screen.getByLabelText("Conteúdo"), { target: { value: "Rascunho em andamento" } });

    expect(mocks.listNotebookNotes).toHaveBeenCalledTimes(1);
    fireEvent.click(within(screen.getByLabelText("Filtros de categoria")).getByRole("button", { name: "Todas" }));

    expect(mocks.listNotebookNotes).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("heading", { name: "Editar nota" })).toBeInTheDocument();
    expect(screen.getByDisplayValue("Rascunho em andamento")).toBeInTheDocument();
  });

  test("trocar filtro mantem layout e editor montados durante refetch atrasado", async () => {
    let resolveFilteredNotes: (notes: NotebookNote[]) => void = () => undefined;
    mocks.listNotebookNotes
      .mockResolvedValueOnce([pesquisaNote, resolvedNote])
      .mockImplementation((_bookId: string, categoryId?: string | null) => {
        if (categoryId === pesquisaCategory.id) {
          return new Promise<NotebookNote[]>((resolve) => {
            resolveFilteredNotes = resolve;
          });
        }
        return Promise.resolve([pesquisaNote, resolvedNote]);
      });
    renderWithClient(<NotebookPanel bookId="book-1" />);

    const notesList = await screen.findByLabelText("Notas do caderno");
    fireEvent.click(within(notesList).getByText(resolvedNote.title));
    fireEvent.change(screen.getByLabelText("Conteúdo"), { target: { value: "Edicao que nao pode sumir" } });

    fireEvent.click(screen.getByRole("button", { name: "Pesquisa" }));

    expect(screen.getByLabelText("Navegação do caderno")).toBeInTheDocument();
    expect(screen.getByLabelText("Editor do caderno")).toBeInTheDocument();
    expect(screen.queryByText("Carregando caderno...")).not.toBeInTheDocument();
    expect(screen.getByDisplayValue("Edicao que nao pode sumir")).toBeInTheDocument();
    expect(screen.getByText("Pergunta respondida")).toBeInTheDocument();

    resolveFilteredNotes([pesquisaNote]);

    await waitFor(() => {
      expect(mocks.listNotebookNotes).toHaveBeenLastCalledWith("book-1", pesquisaCategory.id);
    });
    expect(screen.getByDisplayValue("Edicao que nao pode sumir")).toBeInTheDocument();
  });

  test("salvar nota selecionada apos excluir categoria nao reenvia categoria excluida", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    renderWithClient(<NotebookPanel bookId="book-1" />);

    const notesList = await screen.findByLabelText("Notas do caderno");
    fireEvent.click(within(notesList).getByText(resolvedNote.title));
    fireEvent.click(screen.getByRole("button", { name: "Gerenciar categorias" }));
    const customCategoryRow = screen.getByRole("group", { name: "Categoria Cronologia" });
    fireEvent.click(within(customCategoryRow).getByRole("button", { name: "Excluir" }));

    await waitFor(() => {
      expect(mocks.deleteNotebookCategory).toHaveBeenCalledWith(customCategory.id);
    });
    fireEvent.click(screen.getByRole("button", { name: "Salvar nota" }));

    await waitFor(() => {
      expect(mocks.updateNotebookNote).toHaveBeenCalledWith(resolvedNote.id, {
        title: resolvedNote.title,
        content: resolvedNote.content,
        categoryId: null,
        status: resolvedNote.status,
      });
    });
    expect(mocks.updateNotebookNote).not.toHaveBeenCalledWith(
      resolvedNote.id,
      expect.objectContaining({ categoryId: customCategory.id })
    );
  });
});

function categoryFixture(id: string, name: string, sortOrder: number): NotebookCategory {
  return {
    id,
    bookId: "book-1",
    name,
    sortOrder,
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
