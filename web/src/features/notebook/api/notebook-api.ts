import type {
  NotebookCategory,
  NotebookNote,
  NotebookNoteRequest,
  NotebookNoteUpdateRequest,
} from "@/features/notebook/types";
import { apiRequest } from "@/lib/api/client";

export function listNotebookCategories(bookId: string) {
  return apiRequest<NotebookCategory[]>(`/api/books/${bookId}/notebook/categories`);
}

export function listNotebookNotes(bookId: string, categoryId?: string | null) {
  const query = categoryId ? `?categoryId=${encodeURIComponent(categoryId)}` : "";
  return apiRequest<NotebookNote[]>(`/api/books/${bookId}/notebook/notes${query}`);
}

export function createNotebookNote(bookId: string, payload: NotebookNoteRequest) {
  return apiRequest<NotebookNote>(`/api/books/${bookId}/notebook/notes`, {
    method: "POST",
    body: payload,
  });
}

export function updateNotebookNote(noteId: string, payload: NotebookNoteUpdateRequest) {
  return apiRequest<NotebookNote>(`/api/notebook/notes/${noteId}`, {
    method: "PATCH",
    body: payload,
  });
}

export function deleteNotebookNote(noteId: string) {
  return apiRequest<void>(`/api/notebook/notes/${noteId}`, {
    method: "DELETE",
  });
}
