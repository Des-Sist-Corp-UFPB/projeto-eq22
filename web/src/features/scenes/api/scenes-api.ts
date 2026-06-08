import { apiRequest } from "@/lib/api/client";
import type {
  Scene,
  SceneVersionDetail,
  SceneVersionPage,
  UpdateSceneContentRequest,
  UpdateSceneRequest,
} from "@/features/scenes/types";

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

export function listSceneVersions(sceneId: string, page = 0, size = 20) {
  return apiRequest<SceneVersionPage>(`/api/scenes/${sceneId}/versions?page=${page}&size=${size}`);
}

export function getSceneVersion(sceneId: string, versionId: string) {
  return apiRequest<SceneVersionDetail>(`/api/scenes/${sceneId}/versions/${versionId}`);
}

export function restoreSceneVersion(sceneId: string, versionId: string, expectedContentRevision: number) {
  return apiRequest<Scene>(`/api/scenes/${sceneId}/versions/${versionId}/restore`, {
    method: "POST",
    body: { expectedContentRevision },
  });
}
