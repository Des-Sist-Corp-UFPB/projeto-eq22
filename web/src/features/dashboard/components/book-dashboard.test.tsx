import { fireEvent, screen, within } from "@testing-library/react";
import React from "react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { BookDashboard } from "@/features/dashboard/components/book-dashboard";
import { characterAda, dashboardWithScenes, emptyDashboard, itemKey, locationLibrary } from "@/test/fixtures";
import { renderWithClient } from "@/test/test-utils";

const mocks = vi.hoisted(() => ({
  useBookDashboard: vi.fn(),
  useCharacter: vi.fn(),
  useLocation: vi.fn(),
  useItem: vi.fn(),
  updateBook: vi.fn(),
}));

vi.mock("@/features/dashboard/api/dashboard-hooks", () => ({
  useBookDashboard: mocks.useBookDashboard,
}));

vi.mock("@/features/characters/api/characters-hooks", () => ({
  useCharacter: mocks.useCharacter,
}));

vi.mock("@/features/locations/api/locations-hooks", () => ({
  useLocation: mocks.useLocation,
}));

vi.mock("@/features/items/api/items-hooks", () => ({
  useItem: mocks.useItem,
}));

vi.mock("@/features/books/api/books-api", () => ({
  updateBook: mocks.updateBook,
}));

describe("BookDashboard", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.useCharacter.mockReturnValue({ isLoading: false, isError: false, data: characterAda });
    mocks.useLocation.mockReturnValue({ isLoading: false, isError: false, data: locationLibrary });
    mocks.useItem.mockReturnValue({ isLoading: false, isError: false, data: itemKey });
  });

  test("renderiza estado de loading", () => {
    mocks.useBookDashboard.mockReturnValue({ isLoading: true, isError: false, data: undefined });

    renderWithClient(<BookDashboard bookId="book-1" />);

    expect(screen.getByText(/Carregando/)).toBeInTheDocument();
  });

  test("renderiza estado de erro", () => {
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: true, data: undefined });

    renderWithClient(<BookDashboard bookId="book-1" />);

    expect(screen.getByText(/backend/)).toBeInTheDocument();
  });

  test("renderiza livro sem cenas e estado sem meta", () => {
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: emptyDashboard });

    renderWithClient(<BookDashboard bookId="book-1" />);

    expect(screen.getByText("Total de palavras")).toBeInTheDocument();
    expect(screen.getByText("Total de cenas")).toBeInTheDocument();
    expect(screen.getByText(/ainda.*cenas/i)).toBeInTheDocument();
    expect(screen.getByText("Nenhuma meta de palavras definida.")).toBeInTheDocument();
  });

  test("mostra cards principais, meta existente e meta ultrapassada", () => {
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: dashboardWithScenes });

    renderWithClient(<BookDashboard bookId="book-1" />);

    expect(screen.getByText("Total de palavras")).toBeInTheDocument();
    expect(screen.getByText("1.200 / 1.000 palavras")).toBeInTheDocument();
    expect(screen.getByText("Meta ultrapassada em 200 palavras")).toBeInTheDocument();
    expect(screen.getByText("Planejamento narrativo")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Ver cenas com status Rascunho" })).toBeInTheDocument();
  });

  test("clicar em card de status abre modal e clicar em cena troca o conteudo do mesmo modal", () => {
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: dashboardWithScenes });

    renderWithClient(<BookDashboard bookId="book-1" />);

    fireEvent.click(screen.getByRole("button", { name: "Ver cenas com status Rascunho" }));

    let dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("Cenas em Rascunho")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: /A chave aparece/ })).toBeInTheDocument();

    fireEvent.click(within(dialog).getByRole("button", { name: /A chave aparece/ }));

    dialog = screen.getByRole("dialog");
    expect(screen.getAllByRole("dialog")).toHaveLength(1);
    expect(within(dialog).getAllByRole("heading", { name: "A chave aparece" })).toHaveLength(2);
    expect(within(dialog).getByText("Encontrar a chave")).toBeInTheDocument();
  });

  test("clicar em lacuna abre lista de cenas afetadas", () => {
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: dashboardWithScenes });

    renderWithClient(<BookDashboard bookId="book-1" />);

    fireEvent.click(screen.getByRole("button", { name: /Sem objetivo/ }));

    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("Cenas sem objetivo")).toBeInTheDocument();
    expect(within(dialog).getByText("1 cenas afetadas.")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: /Cena sem objetivo/ })).toBeInTheDocument();
  });

  test("clicar em personagem, localizacao e item abre seus detalhes", () => {
    mocks.useBookDashboard.mockReturnValue({ isLoading: false, isError: false, data: dashboardWithScenes });

    renderWithClient(<BookDashboard bookId="book-1" />);

    fireEvent.click(screen.getAllByRole("button", { name: /Ada/ })[0]);
    expect(within(screen.getByRole("dialog")).getByText("Cenas como POV")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Fechar" }));

    fireEvent.click(screen.getByRole("button", { name: /Biblioteca/ }));
    expect(within(screen.getByRole("dialog")).getByText(/Cenas como localiza/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Fechar" }));

    fireEvent.click(screen.getByRole("button", { name: /Chave de prata/ }));
    const itemDialog = screen.getByRole("dialog");
    expect(within(itemDialog).getByText("Dono atual")).toBeInTheDocument();
    expect(within(itemDialog).getByText("Ada")).toBeInTheDocument();
    expect(within(itemDialog).queryByText(characterAda.id)).not.toBeInTheDocument();
  });
});
