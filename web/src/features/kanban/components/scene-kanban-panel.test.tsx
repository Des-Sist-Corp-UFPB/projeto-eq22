import { fireEvent, screen, waitFor } from "@testing-library/react";
import React from "react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { SceneKanbanPanel } from "@/features/kanban/components/scene-kanban-panel";
import type { BookOutline } from "@/features/outline/types";
import { updateScene } from "@/features/scenes/api/scenes-api";
import type { SceneStatus } from "@/features/scenes/types";
import { renderWithClient } from "@/test/test-utils";

vi.mock("@/features/scenes/api/scenes-api", () => ({
  updateScene: vi.fn(),
}));

const updateSceneMock = vi.mocked(updateScene);

describe("SceneKanbanPanel", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    updateSceneMock.mockResolvedValue({
      id: "scene-complete",
      status: "PLANNED",
    } as Awaited<ReturnType<typeof updateScene>>);
  });

  test("renders loading, error, and empty states", () => {
    const onOpenSceneInEditor = vi.fn();
    const { rerender } = renderWithClient(
      <SceneKanbanPanel
        bookId="book-1"
        outline={null}
        isLoading
        isError={false}
        onOpenSceneInEditor={onOpenSceneInEditor}
        onOpenScenePlanning={vi.fn()}
      />
    );

    expect(screen.getByText("Carregando kanban...")).toBeInTheDocument();

    rerender(
      <SceneKanbanPanel
        bookId="book-1"
        outline={null}
        isLoading={false}
        isError
        onOpenSceneInEditor={onOpenSceneInEditor}
        onOpenScenePlanning={vi.fn()}
      />
    );
    expect(screen.getByText("Nao foi possivel carregar o kanban.")).toBeInTheDocument();

    rerender(
      <SceneKanbanPanel
        bookId="book-1"
        outline={emptyOutline}
        isLoading={false}
        isError={false}
        onOpenSceneInEditor={onOpenSceneInEditor}
        onOpenScenePlanning={vi.fn()}
      />
    );
    expect(screen.getByText("Este livro ainda nao tem cenas.")).toBeInTheDocument();
  });

  test("renders columns, gap badge, and opens scene cards", () => {
    const onOpenSceneInEditor = vi.fn();
    renderKanban(onOpenSceneInEditor);

    expect(screen.getByRole("heading", { name: "Kanban" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Ideia" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Planejada" })).toBeInTheDocument();
    expect(screen.getByText("2 lacunas")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Abrir cena Cena incompleta" }));

    expect(onOpenSceneInEditor).toHaveBeenCalledWith("scene-incomplete");
  });

  test("allows complete scenes to move to planned", async () => {
    renderKanban();

    fireEvent.change(screen.getByLabelText("Status de Cena completa"), { target: { value: "PLANNED" } });

    await waitFor(() => {
      expect(updateSceneMock).toHaveBeenCalledWith("scene-complete", { status: "PLANNED" });
    });
  });

  test("blocks incomplete scenes moving to planned and opens planning CTA", () => {
    const onOpenSceneInEditor = vi.fn();
    const onOpenScenePlanning = vi.fn();
    renderKanban(onOpenSceneInEditor, onOpenScenePlanning);

    fireEvent.change(screen.getByLabelText("Status de Cena incompleta"), { target: { value: "PLANNED" } });

    expect(screen.getByRole("dialog", { name: "Planejamento incompleto" })).toBeInTheDocument();
    expect(screen.getByText("POV")).toBeInTheDocument();
    expect(screen.getByText("Objetivo")).toBeInTheDocument();
    expect(updateSceneMock).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Abrir planejamento" }));

    expect(onOpenScenePlanning).toHaveBeenCalledWith("scene-incomplete");
    expect(onOpenSceneInEditor).not.toHaveBeenCalled();
  });

  test("asks confirmation before moving incomplete scenes to advanced statuses", async () => {
    renderKanban();

    fireEvent.change(screen.getByLabelText("Status de Cena incompleta"), { target: { value: "WRITTEN" } });

    expect(screen.getByRole("dialog", { name: "Avancar com lacunas?" })).toBeInTheDocument();
    expect(updateSceneMock).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Mover mesmo assim" }));

    await waitFor(() => {
      expect(updateSceneMock).toHaveBeenCalledWith("scene-incomplete", { status: "WRITTEN" });
    });
  });

  test("clears drag overlay when keyboard drag is cancelled", async () => {
    renderKanban();

    const dragHandle = screen.getByRole("button", { name: "Mover cena Cena completa" });
    dragHandle.focus();
    fireEvent.keyDown(dragHandle, { key: " ", code: "Space" });

    await waitFor(() => {
      expect(screen.getAllByRole("button", { name: "Abrir cena Cena completa" })).toHaveLength(2);
    });

    fireEvent.keyDown(dragHandle, { key: "Escape", code: "Escape" });

    await waitFor(() => {
      expect(screen.getAllByRole("button", { name: "Abrir cena Cena completa" })).toHaveLength(1);
    });
  });

  test("rolls back optimistic status after API failure", async () => {
    updateSceneMock.mockRejectedValueOnce(new Error("Falha"));
    const onOpenSceneInEditor = vi.fn();
    const { rerender } = renderKanban(onOpenSceneInEditor);

    fireEvent.change(screen.getByLabelText("Status de Cena completa"), { target: { value: "PLANNED" } });

    expect(await screen.findByText("Nao foi possivel mover a cena agora. A coluna foi restaurada.")).toBeInTheDocument();
    expect(screen.getByLabelText("Status de Cena completa")).toHaveValue("DRAFT");

    rerender(
      <SceneKanbanPanel
        bookId="book-1"
        outline={outlineWithSceneStatus("scene-complete", "REVISED")}
        isLoading={false}
        isError={false}
        onOpenSceneInEditor={onOpenSceneInEditor}
        onOpenScenePlanning={vi.fn()}
      />
    );

    expect(screen.getByLabelText("Status de Cena completa")).toHaveValue("REVISED");
  });
});

function renderKanban(onOpenSceneInEditor = vi.fn(), onOpenScenePlanning = vi.fn()) {
  return renderWithClient(
    <SceneKanbanPanel
      bookId="book-1"
      outline={outlineWithScenes}
      isLoading={false}
      isError={false}
      onOpenSceneInEditor={onOpenSceneInEditor}
      onOpenScenePlanning={onOpenScenePlanning}
    />
  );
}

function outlineWithSceneStatus(sceneId: string, status: SceneStatus): BookOutline {
  return {
    ...outlineWithScenes,
    sections: outlineWithScenes.sections.map((section) => ({
      ...section,
      chapters: section.chapters.map((chapter) => ({
        ...chapter,
        scenes: chapter.scenes.map((scene) => (scene.id === sceneId ? { ...scene, status } : scene)),
      })),
    })),
  };
}

const emptyOutline: BookOutline = {
  id: "book-1",
  title: "Livro vazio",
  status: "WRITING",
  wordCount: 0,
  sections: [],
};

const outlineWithScenes: BookOutline = {
  id: "book-1",
  title: "Livro",
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
              id: "scene-complete",
              title: "Cena completa",
              status: "DRAFT",
              sortOrder: 0,
              wordCount: 900,
              povCharacterId: "ada",
              povCharacterName: "Ada",
              planningGaps: [],
            },
            {
              id: "scene-incomplete",
              title: "Cena incompleta",
              status: "IDEA",
              sortOrder: 1,
              wordCount: 300,
              povCharacterId: null,
              povCharacterName: null,
              planningGaps: ["POV", "Objetivo"],
            },
          ],
        },
      ],
    },
  ],
};
