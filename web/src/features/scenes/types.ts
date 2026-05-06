export type SceneStatus = "IDEA" | "PLANNED" | "DRAFT" | "WRITTEN" | "REVISED" | "FINAL";

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
