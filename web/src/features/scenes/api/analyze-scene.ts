import { apiRequest } from "@/lib/api/client";

export type SceneAnalysisRequest = {
  focus?: string;
};

export type SceneAnalysisResult = {
  summary: string;
  tone: string;
  pacing: string;
  strengths: string[];
  issues: string[];
  suggestions: string[];
};

export async function analyzeScene(
  sceneId: string,
  request: SceneAnalysisRequest,
  signal?: AbortSignal
): Promise<SceneAnalysisResult> {
  const focus = request.focus?.trim();

  return apiRequest<SceneAnalysisResult>(`/api/scenes/${sceneId}/ai-analysis`, {
    method: "POST",
    ...(focus ? { body: { focus } } : {}),
    signal,
  });
}
