import type { SceneStatus } from "@/features/scenes/types";

export type PlanningProgressResponse = {
  plannedScenesCount: number;
  totalScenes: number;
  plannedScenesPercent: number;
};

export type StatusCountResponse = {
  status: SceneStatus;
  scenesCount: number;
  wordCount: number;
};

export type PovStatsResponse = {
  characterId: string;
  name: string;
  scenesCount: number;
  wordCount: number;
};

export type NarrativeGapsResponse = {
  scenesWithoutPov: number;
  scenesWithoutGoal: number;
  scenesWithoutConflict: number;
  scenesWithoutOutcome: number;
  scenesWithoutMainLocation: number;
  scenesWithoutParticipants: number;
};

export type EntityUsageResponse = {
  id: string;
  name: string;
  scenesCount: number;
};

export type BookDashboardResponse = {
  bookId: string;
  title: string;
  totalWordCount: number;
  totalSections: number;
  totalChapters: number;
  totalScenes: number;
  planningProgress: PlanningProgressResponse;
  scenesByStatus: StatusCountResponse[];
  povStats: PovStatsResponse[];
  narrativeGaps: NarrativeGapsResponse;
  mostUsedCharacters: EntityUsageResponse[];
  mostUsedLocations: EntityUsageResponse[];
  mostUsedItems: EntityUsageResponse[];
};
