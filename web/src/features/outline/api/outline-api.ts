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

export type UpdateSectionRequest = {
  title?: string;
  type?: SectionType;
  sortOrder?: number;
};

export type UpdateChapterRequest = {
  title?: string;
  summary?: string;
  sortOrder?: number;
};

export type ReorderRequest = {
  orderedIds: string[];
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

export function updateSection(sectionId: string, request: UpdateSectionRequest) {
  return apiRequest(`/api/sections/${sectionId}`, {
    method: "PATCH",
    body: request,
  });
}

export function deleteSection(sectionId: string) {
  return apiRequest<void>(`/api/sections/${sectionId}`, {
    method: "DELETE",
  });
}

export function reorderSections(bookId: string, orderedIds: string[]) {
  return apiRequest<void>(`/api/books/${bookId}/sections/reorder`, {
    method: "PATCH",
    body: { orderedIds } satisfies ReorderRequest,
  });
}

export function createChapter(sectionId: string, request: CreateChapterRequest) {
  return apiRequest(`/api/sections/${sectionId}/chapters`, {
    method: "POST",
    body: request,
  });
}

export function updateChapter(chapterId: string, request: UpdateChapterRequest) {
  return apiRequest(`/api/chapters/${chapterId}`, {
    method: "PATCH",
    body: request,
  });
}

export function deleteChapter(chapterId: string) {
  return apiRequest<void>(`/api/chapters/${chapterId}`, {
    method: "DELETE",
  });
}

export function reorderChapters(sectionId: string, orderedIds: string[]) {
  return apiRequest<void>(`/api/sections/${sectionId}/chapters/reorder`, {
    method: "PATCH",
    body: { orderedIds } satisfies ReorderRequest,
  });
}

export function createScene(chapterId: string, request: CreateSceneRequest) {
  return apiRequest<Scene>(`/api/chapters/${chapterId}/scenes`, {
    method: "POST",
    body: request,
  });
}

export function deleteScene(sceneId: string) {
  return apiRequest<void>(`/api/scenes/${sceneId}`, {
    method: "DELETE",
  });
}

export function reorderScenes(chapterId: string, orderedIds: string[]) {
  return apiRequest<void>(`/api/chapters/${chapterId}/scenes/reorder`, {
    method: "PATCH",
    body: { orderedIds } satisfies ReorderRequest,
  });
}
