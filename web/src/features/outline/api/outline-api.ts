import { apiRequest } from "@/lib/api/client";
import type { BookOutline, SectionType } from "@/features/outline/types";
import type { Scene, SceneStatus } from "@/features/scenes/types";

export type CreateSectionRequest = {
  title: string;
  type?: SectionType;
  sortOrder?: number;
};

export type CreateChapterRequest = {
  title: string;
  summary?: string;
  sortOrder?: number;
};

export type CreateSceneRequest = {
  title: string;
  summary?: string;
  status?: SceneStatus;
  sortOrder?: number;
  contentText?: string;
  contentJson?: string;
};

export function getOutline(bookId: string) {
  return apiRequest<BookOutline>(`/api/books/${bookId}/outline`);
}

export function createSection(bookId: string, request: CreateSectionRequest) {
  return apiRequest(`/api/books/${bookId}/sections`, {
    method: "POST",
    body: request,
  });
}

export function createChapter(sectionId: string, request: CreateChapterRequest) {
  return apiRequest(`/api/sections/${sectionId}/chapters`, {
    method: "POST",
    body: request,
  });
}

export function createScene(chapterId: string, request: CreateSceneRequest) {
  return apiRequest<Scene>(`/api/chapters/${chapterId}/scenes`, {
    method: "POST",
    body: request,
  });
}
