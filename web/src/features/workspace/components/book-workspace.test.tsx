import { fireEvent, screen, waitFor } from "@testing-library/react";
import React from "react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { BookWorkspace } from "@/features/workspace/components/book-workspace";
import type { BookOutline } from "@/features/outline/types";
import { sceneForPlanning } from "@/test/fixtures";
import { renderWithClient } from "@/test/test-utils";

const outline: BookOutline = {
  id: "book-1",
  title: "Livro de teste",
  status: "WRITING",
  wordCount: 1200,
  sections: [
    {
      id: "section-1",
      title: "Parte 1",
      type: "PART",
      sortOrder: 0,
      wordCount: 1200,
      chapters: [
        {
          id: "chapter-1",
          title: "Capitulo 1",
          summary: null,
          sortOrder: 0,
          wordCount: 1200,
          scenes: [
            {
              id: sceneForPlanning.id,
              title: sceneForPlanning.title,
              status: sceneForPlanning.status,
              sortOrder: sceneForPlanning.sortOrder,
              wordCount: sceneForPlanning.wordCount,
            },
          ],
        },
      ],
    },
  ],
};

const mocks = vi.hoisted(() => ({
  getOutline: vi.fn(),
  getScene: vi.fn(),
  updateScene: vi.fn(),
  updateSceneContent: vi.fn(),
  deleteScene: vi.fn(),
}));

vi.mock("@/features/outline/api/outline-api", async () => {
  const actual = await vi.importActual<typeof import("@/features/outline/api/outline-api")>("@/features/outline/api/outline-api");

  return {
    ...actual,
    getOutline: mocks.getOutline,
  };
});

vi.mock("@/features/scenes/api/scenes-api", () => ({
  getScene: mocks.getScene,
  updateScene: mocks.updateScene,
  updateSceneContent: mocks.updateSceneContent,
  deleteScene: mocks.deleteScene,
}));

vi.mock("@/features/scenes/editor/tiptap-editor", () => ({
  TiptapEditor: ({ initialContentText }: { initialContentText?: string | null }) => (
    <textarea aria-label="Editor de conteúdo" readOnly value={initialContentText ?? ""} />
  ),
}));

describe("BookWorkspace focus mode", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
    mocks.getOutline.mockResolvedValue(outline);
    mocks.getScene.mockResolvedValue(sceneForPlanning);
    mocks.updateScene.mockResolvedValue(sceneForPlanning);
    mocks.updateSceneContent.mockResolvedValue(sceneForPlanning);
    mocks.deleteScene.mockResolvedValue(undefined);
  });

  test("oculta o outline no foco e restaura mantendo a cena selecionada", async () => {
    renderWithClient(<BookWorkspace bookId="book-1" />);

    expect(await screen.findByText("Livro")).toBeInTheDocument();

    const sceneRow = screen.getByText(sceneForPlanning.title).closest("button");
    expect(sceneRow).not.toBeNull();
    fireEvent.click(sceneRow as HTMLButtonElement);

    expect(await screen.findByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();
    expect(screen.getAllByText(`${sceneForPlanning.wordCount} palavras`).length).toBeGreaterThan(0);
    expect(screen.getAllByText("Salvo").length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: /Salvar conte.do/ })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Modo foco" }));

    await waitFor(() => {
      expect(screen.queryByText("Livro")).not.toBeInTheDocument();
    });
    expect(screen.getByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();
    expect(screen.getAllByText(`${sceneForPlanning.wordCount} palavras`).length).toBeGreaterThan(0);
    expect(screen.getAllByText("Salvo").length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: /Salvar conte.do/ })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Sair do foco" }));

    expect(await screen.findByText("Livro")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: sceneForPlanning.title })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Modo foco" })).toBeInTheDocument();
  });
});
