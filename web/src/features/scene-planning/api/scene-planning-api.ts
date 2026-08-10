import type { ScenePlanningRequest } from "@/features/scene-planning/types";
import type { Scene } from "@/features/scenes/types";
import { apiRequest } from "@/lib/api/client";

export function updateScenePlanning(sceneId: string, payload: ScenePlanningRequest) {
  return apiRequest<Scene>(`/api/scenes/${sceneId}/planning`, {
    method: "PATCH",
    body: payload,
  });
}
