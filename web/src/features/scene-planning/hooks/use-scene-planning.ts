"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { updateScenePlanning } from "@/features/scene-planning/api/scene-planning-api";
import type { ScenePlanningRequest } from "@/features/scene-planning/types";
import { queryKeys } from "@/lib/query/keys";

export function useUpdateScenePlanning(bookId: string, sceneId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (payload: ScenePlanningRequest) => updateScenePlanning(sceneId, payload),
    onSuccess: (scene) => {
      queryClient.setQueryData(queryKeys.scene(scene.id), scene);
    },
  });
}
