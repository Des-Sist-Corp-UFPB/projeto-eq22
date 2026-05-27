export type SceneStatus = "IDEA" | "PLANNED" | "DRAFT" | "WRITTEN" | "REVISED" | "FINAL";

export type SceneCharacterSummary = {
  id: string;
  name: string;
};

export type SceneLocationSummary = {
  id: string;
  name: string;
};

export type SceneItemSummary = {
  id: string;
  name: string;
};

export type Scene = {
  id: string;
  bookId: string;
  chapterId: string;
  title: string;
  summary: string | null;
  contentJson: string | null;
  contentText: string | null;
  status: SceneStatus;
  sortOrder: number;
  wordCount: number;
  goal: string | null;
  conflict: string | null;
  outcome: string | null;
  planningNotes: string | null;
  povCharacter: SceneCharacterSummary | null;
  mainLocation: SceneLocationSummary | null;
  participantCharacters: SceneCharacterSummary[];
  items: SceneItemSummary[];
  createdAt: string;
  updatedAt: string;
};

export type UpdateSceneRequest = {
  title?: string;
  summary?: string;
  status?: SceneStatus;
  sortOrder?: number;
};

export type UpdateSceneContentRequest = {
  contentJson?: string;
  contentText?: string;
};
