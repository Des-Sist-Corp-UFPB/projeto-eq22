import { describe, expect, test } from "vitest";
import type { BookOutline } from "@/features/outline/types";
import { buildStoryboardModel, NO_POV_LANE_ID } from "@/features/storyboard/utils/storyboard-model";

describe("buildStoryboardModel", () => {
  test("orders manuscript hierarchy and derives POV lanes by first appearance", () => {
    const model = buildStoryboardModel(storyboardOutline);

    expect(model.sections.map((section) => section.title)).toEqual(["Parte 1", "Parte 2"]);
    expect(model.chapters.map((chapter) => chapter.title)).toEqual(["Capitulo 1", "Capitulo 2", "Capitulo 3"]);
    expect(model.scenes.map((scene) => scene.scene.title)).toEqual([
      "Cena Ada inicial",
      "Cena sem POV",
      "Cena Bruno",
      "Cena Ada final",
    ]);
    expect(model.lanes.map((lane) => lane.label)).toEqual(["Ada", "Bruno", "Sem POV"]);
    expect(model.lanes.at(-1)?.id).toBe(NO_POV_LANE_ID);
    expect(model.scenes.find((scene) => scene.scene.title === "Cena sem POV")?.laneId).toBe(NO_POV_LANE_ID);
  });
});

const storyboardOutline: BookOutline = {
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
              id: "scene-4",
              title: "Cena Ada final",
              sortOrder: 0,
              povCharacterId: "ada",
              povCharacterName: "Ada",
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
              id: "scene-3",
              title: "Cena Bruno",
              sortOrder: 0,
              povCharacterId: "bruno",
              povCharacterName: "Bruno",
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
              id: "scene-2",
              title: "Cena sem POV",
              sortOrder: 1,
              povCharacterId: null,
              povCharacterName: null,
              planningGaps: ["POV"],
            }),
            scene({
              id: "scene-1",
              title: "Cena Ada inicial",
              sortOrder: 0,
              povCharacterId: "ada",
              povCharacterName: "Ada",
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
  sortOrder,
  povCharacterId,
  povCharacterName,
  planningGaps = [],
}: {
  id: string;
  title: string;
  sortOrder: number;
  povCharacterId: string | null;
  povCharacterName: string | null;
  planningGaps?: string[];
}) {
  return {
    id,
    title,
    status: "DRAFT" as const,
    sortOrder,
    wordCount: 100,
    povCharacterId,
    povCharacterName,
    planningGaps,
  };
}
