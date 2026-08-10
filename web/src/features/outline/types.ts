import type { BookStatus } from "@/features/books/types";
import type { SceneStatus } from "@/features/scenes/types";

export type SectionType = "PART" | "PROLOGUE" | "INTERLUDE" | "EPILOGUE" | "OTHER";

export type OutlineScene = {
  id: string;
  title: string;
  status: SceneStatus;
  sortOrder: number;
  wordCount: number;
  povCharacterId: string | null;
  povCharacterName: string | null;
  planningGaps: string[];
};

export type OutlineChapter = {
  id: string;
  title: string;
  summary: string | null;
  sortOrder: number;
  wordCount: number;
  scenes: OutlineScene[];
};

export type OutlineSection = {
  id: string;
  title: string;
  type: SectionType;
  sortOrder: number;
  wordCount: number;
  chapters: OutlineChapter[];
};

export type BookOutline = {
  id: string;
  title: string;
  status: BookStatus;
  wordCount: number;
  sections: OutlineSection[];
};
