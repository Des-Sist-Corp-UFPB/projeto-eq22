import { describe, expect, test } from "vitest";
import type { BookOutline } from "@/features/outline/types";
import type { SceneStatus } from "@/features/scenes/types";
import {
  buildKanbanModel,
  getKanbanTransitionDecision,
  KANBAN_STATUS_COLUMNS,
  reconcileKanbanStatusOverrides,
} from "@/features/kanban/utils/kanban-model";

describe("buildKanbanModel", () => {
  test("groups scenes into all status columns in workflow order", () => {
    const model = buildKanbanModel(kanbanOutline);

    expect(model.columns.map((column) => column.status)).toEqual(
      KANBAN_STATUS_COLUMNS.map((column) => column.status)
    );
    expect(model.columns.map((column) => column.label)).toEqual([
      "Ideia",
      "Planejada",
      "Rascunho",
      "Escrita",
      "Revisada",
      "Final",
    ]);
    expect(model.columns.find((column) => column.status === "IDEA")?.scenes.map((scene) => scene.scene.title)).toEqual([
      "Cena ideia",
    ]);
    expect(model.columns.find((column) => column.status === "FINAL")?.scenes).toEqual([]);
  });

  test("preserves canonical manuscript order inside each status column", () => {
    const model = buildKanbanModel(kanbanOutline);

    expect(model.columns.find((column) => column.status === "DRAFT")?.scenes.map((scene) => scene.scene.title)).toEqual([
      "Cena rascunho inicial",
      "Cena rascunho final",
    ]);
  });

  test("decides status transitions from planning gaps", () => {
    const model = buildKanbanModel(kanbanOutline);
    const incompleteScene = model.scenes.find((scene) => scene.id === "scene-idea");
    const completeScene = model.scenes.find((scene) => scene.id === "scene-draft-1");

    expect(incompleteScene).toBeDefined();
    expect(completeScene).toBeDefined();

    expect(getKanbanTransitionDecision(incompleteScene!, "IDEA")).toEqual({ type: "noop" });
    expect(getKanbanTransitionDecision(incompleteScene!, "PLANNED")).toEqual({ type: "blocked-planned" });
    expect(getKanbanTransitionDecision(incompleteScene!, "WRITTEN")).toEqual({ type: "confirm-advanced" });
    expect(getKanbanTransitionDecision(completeScene!, "PLANNED")).toEqual({ type: "allowed" });
  });

  test("clears a successful override after outline reflects the moved status", () => {
    const refreshedOutline = outlineWithSceneStatus("scene-draft-1", "PLANNED");

    expect(reconcileKanbanStatusOverrides(refreshedOutline, { "scene-draft-1": "PLANNED" })).toEqual({});
  });

  test("trusts a later outline refetch instead of shadowing it with a stale override", () => {
    const refreshedOutline = outlineWithSceneStatus("scene-draft-1", "REVISED");
    const overrides = reconcileKanbanStatusOverrides(refreshedOutline, { "scene-draft-1": "PLANNED" });
    const model = buildKanbanModel(refreshedOutline, overrides);

    expect(overrides).toEqual({});
    expect(model.scenes.find((scene) => scene.id === "scene-draft-1")?.status).toBe("REVISED");
  });
});

const kanbanOutline: BookOutline = {
  id: "book-1",
  title: "Livro",
  status: "WRITING",
  wordCount: 1000,
  sections: [
    {
      id: "section-2",
      title: "Parte 2",
      type: "PART",
      sortOrder: 1,
      wordCount: 200,
      chapters: [
        {
          id: "chapter-3",
          title: "Capitulo 3",
          summary: null,
          sortOrder: 0,
          wordCount: 200,
          scenes: [
            scene({
              id: "scene-draft-2",
              title: "Cena rascunho final",
              status: "DRAFT",
              sortOrder: 0,
              wordCount: 200,
            }),
          ],
        },
      ],
    },
    {
      id: "section-1",
      title: "Parte 1",
      type: "PART",
      sortOrder: 0,
      wordCount: 800,
      chapters: [
        {
          id: "chapter-2",
          title: "Capitulo 2",
          summary: null,
          sortOrder: 1,
          wordCount: 300,
          scenes: [
            scene({
              id: "scene-planned",
              title: "Cena planejada",
              status: "PLANNED",
              sortOrder: 0,
              wordCount: 300,
            }),
          ],
        },
        {
          id: "chapter-1",
          title: "Capitulo 1",
          summary: null,
          sortOrder: 0,
          wordCount: 500,
          scenes: [
            scene({
              id: "scene-idea",
              title: "Cena ideia",
              status: "IDEA",
              sortOrder: 1,
              wordCount: 100,
              planningGaps: ["POV"],
            }),
            scene({
              id: "scene-draft-1",
              title: "Cena rascunho inicial",
              status: "DRAFT",
              sortOrder: 0,
              wordCount: 400,
            }),
          ],
        },
      ],
    },
  ],
};

function scene({
  id,
  title,
  status,
  sortOrder,
  wordCount,
  planningGaps = [],
}: {
  id: string;
  title: string;
  status: "IDEA" | "PLANNED" | "DRAFT" | "WRITTEN" | "REVISED" | "FINAL";
  sortOrder: number;
  wordCount: number;
  planningGaps?: string[];
}) {
  return {
    id,
    title,
    status,
    sortOrder,
    wordCount,
    povCharacterId: planningGaps.length > 0 ? null : "ada",
    povCharacterName: planningGaps.length > 0 ? null : "Ada",
    planningGaps,
  };
}

function outlineWithSceneStatus(sceneId: string, status: SceneStatus): BookOutline {
  return {
    ...kanbanOutline,
    sections: kanbanOutline.sections.map((section) => ({
      ...section,
      chapters: section.chapters.map((chapter) => ({
        ...chapter,
        scenes: chapter.scenes.map((scene) => (scene.id === sceneId ? { ...scene, status } : scene)),
      })),
    })),
  };
}
