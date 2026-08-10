import { fireEvent, render, screen } from "@testing-library/react";
import React from "react";
import { describe, expect, test, vi } from "vitest";
import { SceneStoryboardPanel } from "@/features/storyboard/components/scene-storyboard-panel";
import type { BookOutline } from "@/features/outline/types";

describe("SceneStoryboardPanel", () => {
  test("renders loading, error, and empty states", () => {
    const onOpenSceneInEditor = vi.fn();
    const { rerender } = render(
      <SceneStoryboardPanel
        outline={null}
        isLoading
        isError={false}
        onOpenSceneInEditor={onOpenSceneInEditor}
      />
    );

    expect(screen.getByText("Carregando storyboard...")).toBeInTheDocument();

    rerender(
      <SceneStoryboardPanel
        outline={null}
        isLoading={false}
        isError
        onOpenSceneInEditor={onOpenSceneInEditor}
      />
    );
    expect(screen.getByText("Nao foi possivel carregar o storyboard.")).toBeInTheDocument();

    rerender(
      <SceneStoryboardPanel
        outline={emptyOutline}
        isLoading={false}
        isError={false}
        onOpenSceneInEditor={onOpenSceneInEditor}
      />
    );
    expect(screen.getByText("Este livro ainda nao tem cenas.")).toBeInTheDocument();
  });

  test("renders lanes, planning gap indicators, and opens scene cards", () => {
    const onOpenSceneInEditor = vi.fn();
    render(
      <SceneStoryboardPanel
        outline={outlineWithScenes}
        isLoading={false}
        isError={false}
        onOpenSceneInEditor={onOpenSceneInEditor}
      />
    );

    expect(screen.getAllByText("Ada").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Sem POV").length).toBeGreaterThan(0);
    expect(screen.getAllByText("2 lacunas").length).toBeGreaterThan(0);
    expect(screen.queryByText("0 lacunas")).not.toBeInTheDocument();

    fireEvent.click(screen.getAllByRole("button", { name: "Abrir cena Cena sem POV" })[0]);

    expect(onOpenSceneInEditor).toHaveBeenCalledWith("scene-gap");
  });
});

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
              id: "scene-1",
              title: "Cena Ada",
              status: "DRAFT",
              sortOrder: 0,
              wordCount: 900,
              povCharacterId: "ada",
              povCharacterName: "Ada",
              planningGaps: [],
            },
            {
              id: "scene-gap",
              title: "Cena sem POV",
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
