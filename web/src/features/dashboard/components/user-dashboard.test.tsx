import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import React from "react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { UserDashboard } from "@/features/dashboard/components/user-dashboard";
import { renderWithClient } from "@/test/test-utils";

const mocks = vi.hoisted(() => ({
  useCurrentUserDashboard: vi.fn(),
}));

vi.mock("@/features/dashboard/api/dashboard-hooks", () => ({
  useCurrentUserDashboard: mocks.useCurrentUserDashboard,
}));

const emptyDashboard = {
  period: { value: "7d", startDate: "2026-06-18", endDate: "2026-06-24" },
  summary: {
    productiveWords: 0,
    manuscriptAdjustments: 0,
    writingDays: 0,
    booksWrittenIn: 0,
    currentGlobalWritingStreak: 0,
    bestGlobalWritingStreak: 0,
    writingDaysThisMonth: 0,
  },
  dailySeries: [
    { date: "2026-06-18", productiveWords: 0, manuscriptAdjustments: 0 },
    { date: "2026-06-24", productiveWords: 0, manuscriptAdjustments: 0 },
  ],
  bookContributions: [],
};

const populatedDashboard = {
  period: { value: "7d", startDate: "2026-06-18", endDate: "2026-06-24" },
  summary: {
    productiveWords: 1234,
    manuscriptAdjustments: -55,
    writingDays: 3,
    booksWrittenIn: 2,
    currentGlobalWritingStreak: 2,
    bestGlobalWritingStreak: 5,
    writingDaysThisMonth: 8,
  },
  dailySeries: [
    { date: "2026-06-22", productiveWords: 100, manuscriptAdjustments: 0 },
    { date: "2026-06-23", productiveWords: 0, manuscriptAdjustments: -55 },
    { date: "2026-06-24", productiveWords: 300, manuscriptAdjustments: 5 },
  ],
  bookContributions: [
    { bookId: "book-a", title: "A Primeira História", productiveWords: 300, manuscriptAdjustments: 0, writingDays: 1 },
    { bookId: "book-b", title: "B Segunda História", productiveWords: 934, manuscriptAdjustments: -55, writingDays: 2 },
  ],
};

describe("UserDashboard", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  test("renderiza estado de loading", () => {
    mocks.useCurrentUserDashboard.mockReturnValue({ isLoading: true, isError: false, data: undefined });

    renderWithClient(<UserDashboard />);

    expect(screen.getByText("Carregando dashboard...")).toBeInTheDocument();
  });

  test("renderiza estado de erro", () => {
    mocks.useCurrentUserDashboard.mockReturnValue({ isLoading: false, isError: true, data: undefined });

    renderWithClient(<UserDashboard />);

    expect(screen.getByText("Não foi possível carregar seu dashboard.")).toBeInTheDocument();
  });

  test("renderiza estado vazio valido", () => {
    mocks.useCurrentUserDashboard.mockReturnValue({ isLoading: false, isError: false, data: emptyDashboard });

    renderWithClient(<UserDashboard />);

    expect(screen.getByText("Palavras produtivas")).toBeInTheDocument();
    expect(screen.getByText("Nenhuma escrita produtiva registrada neste período.")).toBeInTheDocument();
    expect(screen.getAllByText("0").length).toBeGreaterThan(0);
  });

  test("renderiza resumo, serie diaria e contribuicoes por livro em ordem fornecida", () => {
    mocks.useCurrentUserDashboard.mockReturnValue({ isLoading: false, isError: false, data: populatedDashboard });

    renderWithClient(<UserDashboard />);

    expect(screen.getByText("Palavras produtivas")).toBeInTheDocument();
    expect(screen.getByText("+1.234")).toBeInTheDocument();
    expect(screen.getByText("Ajustes do manuscrito")).toBeInTheDocument();
    expect(screen.getByText("-55")).toBeInTheDocument();
    expect(screen.getByText("Dias com escrita")).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();
    expect(screen.getByText("Livros escritos")).toBeInTheDocument();
    expect(screen.getByText("2")).toBeInTheDocument();
    expect(screen.getByText("Sequência atual")).toBeInTheDocument();
    expect(screen.getByText("2 dias")).toBeInTheDocument();
    expect(screen.getByText("Melhor sequência")).toBeInTheDocument();
    expect(screen.getByText("5 dias")).toBeInTheDocument();

    const dailySeries = screen.getByTestId("user-dashboard-daily-series");
    expect(within(dailySeries).getByText(/22 de jun/i)).toBeInTheDocument();
    expect(within(dailySeries).getByText("+100 produtivas")).toBeInTheDocument();
    expect(within(dailySeries).getByText("0 produtivas · -55 ajustes")).toBeInTheDocument();
    expect(within(dailySeries).getByText("+300 produtivas · +5 ajustes")).toBeInTheDocument();

    const contributionItems = screen.getAllByTestId("user-dashboard-book-contribution");
    expect(contributionItems).toHaveLength(2);
    expect(within(contributionItems[0]).getByText("A Primeira História")).toBeInTheDocument();
    expect(within(contributionItems[1]).getByText("B Segunda História")).toBeInTheDocument();
  });

  test("trocar periodo chama hook com periodo selecionado", async () => {
    mocks.useCurrentUserDashboard.mockReturnValue({ isLoading: false, isError: false, data: populatedDashboard });

    renderWithClient(<UserDashboard />);

    expect(mocks.useCurrentUserDashboard).toHaveBeenLastCalledWith("7d");
    fireEvent.click(screen.getByRole("button", { name: "30 dias" }));

    await waitFor(() => expect(mocks.useCurrentUserDashboard).toHaveBeenLastCalledWith("30d"));
  });
});
