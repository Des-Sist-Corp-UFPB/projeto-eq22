"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { reorderChapters, reorderScenes, reorderSections } from "@/features/outline/api/outline-api";
import { ApiError } from "@/lib/api/client";
import { queryKeys } from "@/lib/query/keys";

type ReorderVariables = {
  orderedIds: string[];
};

type ReorderChaptersVariables = ReorderVariables & {
  sectionId: string;
};

type ReorderScenesVariables = ReorderVariables & {
  chapterId: string;
};

export function getReorderErrorMessage(error: unknown) {
  if (error instanceof ApiError) {
    return error.message || "Não foi possível reordenar agora. Verifique os itens e tente novamente.";
  }

  return "Não foi possível reordenar agora. Verifique a API e tente novamente.";
}

export function useReorderSectionsMutation(bookId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ orderedIds }: ReorderVariables) => reorderSections(bookId, orderedIds),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.outline(bookId) });
    },
  });
}

export function useReorderChaptersMutation(bookId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ sectionId, orderedIds }: ReorderChaptersVariables) => reorderChapters(sectionId, orderedIds),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.outline(bookId) });
    },
  });
}

export function useReorderScenesMutation(bookId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ chapterId, orderedIds }: ReorderScenesVariables) => reorderScenes(chapterId, orderedIds),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.outline(bookId) });
    },
  });
}
