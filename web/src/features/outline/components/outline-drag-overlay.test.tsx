import { render, screen } from "@testing-library/react";
import React from "react";
import { describe, expect, test } from "vitest";
import { ChapterDragPreview, SceneDragPreview, SectionDragPreview } from "@/features/outline/components/outline-drag-overlay";
import type { OutlineChapter, OutlineScene, OutlineSection } from "@/features/outline/types";

const scene: OutlineScene = {
  id: "scene-1",
  title: "Cena escondida",
  status: "DRAFT",
  sortOrder: 0,
  wordCount: 300,
  povCharacterId: null,
  povCharacterName: null,
  planningGaps: [],
};

const chapter: OutlineChapter = {
  id: "chapter-1",
  title: "Capitulo 1",
  summary: null,
  sortOrder: 0,
  wordCount: 300,
  scenes: [scene],
};

const section: OutlineSection = {
  id: "section-1",
  title: "Parte 1",
  type: "PART",
  sortOrder: 0,
  wordCount: 700,
  chapters: [
    chapter,
    {
      ...chapter,
      id: "chapter-2",
      title: "Capitulo escondido",
      scenes: [{ ...scene, id: "scene-2", title: "Outra cena escondida", wordCount: 400 }],
      wordCount: 400,
    },
  ],
};

function expectMetric(label: string, value: number) {
  expect(screen.getByText((_content, element) => element?.textContent === `${value} ${label}`)).toBeInTheDocument();
}

describe("outline drag previews", () => {
  test("preview de secao mostra resumo compacto sem renderizar capitulos ou cenas", () => {
    render(<SectionDragPreview section={section} />);

    expect(screen.getByText("Parte 1")).toBeInTheDocument();
    expectMetric("capitulos", 2);
    expectMetric("cenas", 2);
    expectMetric("palavras", 700);
    expect(screen.queryByText("Capitulo escondido")).not.toBeInTheDocument();
    expect(screen.queryByText("Outra cena escondida")).not.toBeInTheDocument();
  });

  test("preview de capitulo mostra resumo compacto sem renderizar cenas", () => {
    render(<ChapterDragPreview chapter={chapter} />);

    expect(screen.getByText("Capitulo 1")).toBeInTheDocument();
    expectMetric("cenas", 1);
    expectMetric("palavras", 300);
    expect(screen.queryByText("Cena escondida")).not.toBeInTheDocument();
  });

  test("preview de cena mostra titulo, status e palavras", () => {
    render(<SceneDragPreview scene={scene} />);

    expect(screen.getByText("Cena escondida")).toBeInTheDocument();
    expect(screen.getByText("DRAFT")).toBeInTheDocument();
    expectMetric("palavras", 300);
  });
});
