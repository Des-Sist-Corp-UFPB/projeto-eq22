import { apiRequest } from "@/lib/api/client";
import type { Scene, UpdateSceneContentRequest, UpdateSceneRequest } from "@/features/scenes/types";

export function getScene(sceneId: string) {
  return apiRequest<Scene>(`/api/scenes/${sceneId}`);
}

export function updateScene(sceneId: string, request: UpdateSceneRequest) {
  return apiRequest<Scene>(`/api/scenes/${sceneId}`, {
    method: "PATCH",
    body: request,
  });
}

export function updateSceneContent(sceneId: string, request: UpdateSceneContentRequest) {
  return apiRequest<Scene>(`/api/scenes/${sceneId}/content`, {
    method: "PATCH",
    body: request,
  });
}

export function deleteScene(sceneId: string) {
  return apiRequest<void>(`/api/scenes/${sceneId}`, {
    method: "DELETE",
  });
}
