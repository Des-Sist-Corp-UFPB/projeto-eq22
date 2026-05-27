"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  createNotebookCategory,
  createNotebookNote,
  deleteNotebookCategory,
  deleteNotebookNote,
  listNotebookCategories,
  listNotebookNotes,
  updateNotebookCategory,
  updateNotebookNote,
} from "@/features/notebook/api/notebook-api";
import type {
  NotebookCategoryRequest,
  NotebookCategoryUpdateRequest,
  NotebookNoteRequest,
  NotebookNoteUpdateRequest,
} from "@/features/notebook/types";

type UpdateNotebookNoteVariables = {
  noteId: string;
  payload: NotebookNoteUpdateRequest;
};

type UpdateNotebookCategoryVariables = {
  categoryId: string;
  payload: NotebookCategoryUpdateRequest;
};

export const notebookQueryKeys = {
  categories: (bookId: string) => ["books", bookId, "notebook", "categories"] as const,
  notesRoot: (bookId: string) => ["books", bookId, "notebook", "notes"] as const,
  notes: (bookId: string, categoryId?: string | null) =>
    [...notebookQueryKeys.notesRoot(bookId), categoryId ?? "all"] as const,
};

export function useNotebookCategories(bookId: string) {
  return useQuery({
    queryKey: notebookQueryKeys.categories(bookId),
    queryFn: () => listNotebookCategories(bookId),
    enabled: Boolean(bookId),
  });
}

export function useCreateNotebookCategory(bookId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (payload: NotebookCategoryRequest) => createNotebookCategory(bookId, payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: notebookQueryKeys.categories(bookId) });
    },
  });
}

export function useUpdateNotebookCategory(bookId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ categoryId, payload }: UpdateNotebookCategoryVariables) =>
      updateNotebookCategory(categoryId, payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: notebookQueryKeys.categories(bookId) });
      void queryClient.invalidateQueries({ queryKey: notebookQueryKeys.notesRoot(bookId) });
    },
  });
}

export function useDeleteNotebookCategory(bookId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (categoryId: string) => deleteNotebookCategory(categoryId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: notebookQueryKeys.categories(bookId) });
      void queryClient.invalidateQueries({ queryKey: notebookQueryKeys.notesRoot(bookId) });
    },
  });
}

export function useNotebookNotes(bookId: string, categoryId?: string | null) {
  return useQuery({
    queryKey: notebookQueryKeys.notes(bookId, categoryId),
    queryFn: () => listNotebookNotes(bookId, categoryId),
    enabled: Boolean(bookId),
  });
}

export function useCreateNotebookNote(bookId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (payload: NotebookNoteRequest) => createNotebookNote(bookId, payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: notebookQueryKeys.notesRoot(bookId) });
    },
  });
}

export function useUpdateNotebookNote(bookId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ noteId, payload }: UpdateNotebookNoteVariables) => updateNotebookNote(noteId, payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: notebookQueryKeys.notesRoot(bookId) });
    },
  });
}

export function useDeleteNotebookNote(bookId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (noteId: string) => deleteNotebookNote(noteId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: notebookQueryKeys.notesRoot(bookId) });
    },
  });
}
