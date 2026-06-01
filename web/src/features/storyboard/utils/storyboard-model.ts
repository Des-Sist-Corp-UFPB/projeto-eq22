import type { BookOutline, OutlineScene } from "@/features/outline/types";

export const NO_POV_LANE_ID = "no-pov";
export const NO_POV_LANE_LABEL = "Sem POV";

export type StoryboardChapter = {
  id: string;
  title: string;
  sectionId: string;
  sectionTitle: string;
  sortOrder: number;
  chapterIndex: number;
};

export type StoryboardSectionSpan = {
  id: string;
  title: string;
  startChapterIndex: number;
  chapterCount: number;
};

export type StoryboardLane = {
  id: string;
  label: string;
  isNoPov: boolean;
};

export type StoryboardScenePlacement = {
  id: string;
  scene: OutlineScene;
  chapterId: string;
  chapterTitle: string;
  sectionId: string;
  sectionTitle: string;
  laneId: string;
  laneLabel: string;
  chapterIndex: number;
  manuscriptIndex: number;
};

export type StoryboardModel = {
  sections: StoryboardSectionSpan[];
  chapters: StoryboardChapter[];
  lanes: StoryboardLane[];
  scenes: StoryboardScenePlacement[];
};

export function buildStoryboardModel(outline: BookOutline): StoryboardModel {
  const sections: StoryboardSectionSpan[] = [];
  const chapters: StoryboardChapter[] = [];
  const lanes: StoryboardLane[] = [];
  const scenes: StoryboardScenePlacement[] = [];
  const laneIds = new Set<string>();
  let hasNoPovLane = false;
  let manuscriptIndex = 0;

  for (const section of [...outline.sections].sort(compareBySortOrder)) {
    const startChapterIndex = chapters.length;

    for (const chapter of [...section.chapters].sort(compareBySortOrder)) {
      const chapterIndex = chapters.length;
      chapters.push({
        id: chapter.id,
        title: chapter.title,
        sectionId: section.id,
        sectionTitle: section.title,
        sortOrder: chapter.sortOrder,
        chapterIndex,
      });

      for (const scene of [...chapter.scenes].sort(compareBySortOrder)) {
        const laneId = scene.povCharacterId ?? NO_POV_LANE_ID;
        const laneLabel = scene.povCharacterName ?? NO_POV_LANE_LABEL;

        if (scene.povCharacterId && !laneIds.has(laneId)) {
          laneIds.add(laneId);
          lanes.push({ id: laneId, label: laneLabel, isNoPov: false });
        }
        if (!scene.povCharacterId) {
          hasNoPovLane = true;
        }

        scenes.push({
          id: scene.id,
          scene,
          chapterId: chapter.id,
          chapterTitle: chapter.title,
          sectionId: section.id,
          sectionTitle: section.title,
          laneId,
          laneLabel,
          chapterIndex,
          manuscriptIndex,
        });
        manuscriptIndex++;
      }
    }

    const chapterCount = chapters.length - startChapterIndex;
    if (chapterCount > 0) {
      sections.push({
        id: section.id,
        title: section.title,
        startChapterIndex,
        chapterCount,
      });
    }
  }

  if (hasNoPovLane) {
    lanes.push({ id: NO_POV_LANE_ID, label: NO_POV_LANE_LABEL, isNoPov: true });
  }

  return { sections, chapters, lanes, scenes };
}

function compareBySortOrder<T extends { sortOrder: number }>(first: T, second: T) {
  return first.sortOrder - second.sortOrder;
}
