import { beforeEach, describe, expect, test, vi } from "vitest";
import {
  analyzeScene,
  type SceneAnalysisResult,
} from "@/features/scenes/api/analyze-scene";

const analysisResult: SceneAnalysisResult = {
  summary: "Uma descoberta muda o rumo da cena.",
  tone: "Tenso",
  pacing: "Crescente",
  strengths: ["Conflito claro"],
  issues: ["Transição abrupta"],
  suggestions: ["Dar mais espaço à reação"],
};

describe("analyzeScene", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  test("usa o endpoint POST correto", async () => {
    const fetchMock = mockSuccessfulFetch();

    await analyzeScene("scene-1", {});

    expect(fetchMock).toHaveBeenCalledWith(
      "http://localhost:8085/api/scenes/scene-1/ai-analysis",
      expect.objectContaining({ method: "POST" })
    );
  });

  test("envia foco não vazio já aparado", async () => {
    const fetchMock = mockSuccessfulFetch();

    await analyzeScene("scene-1", { focus: "  ritmo e tensão  " });

    expect(fetchMock).toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ focus: "ritmo e tensão" }),
      })
    );
  });

  test("não envia corpo quando o foco está em branco", async () => {
    const fetchMock = mockSuccessfulFetch();

    await analyzeScene("scene-1", { focus: "   " });

    const requestInit = fetchMock.mock.calls[0][1];
    expect(requestInit?.body).toBeUndefined();
    expect(requestInit?.headers).toBeUndefined();
  });

  test("encaminha o AbortSignal ao fetch", async () => {
    const fetchMock = mockSuccessfulFetch();
    const controller = new AbortController();

    await analyzeScene("scene-1", {}, controller.signal);

    expect(fetchMock).toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({ signal: controller.signal })
    );
  });

  test("retorna a resposta estruturada sem alterações", async () => {
    mockSuccessfulFetch();

    await expect(analyzeScene("scene-1", {})).resolves.toEqual(analysisResult);
  });
});

function mockSuccessfulFetch() {
  return vi.spyOn(globalThis, "fetch").mockResolvedValue(
    new Response(JSON.stringify(analysisResult), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    })
  );
}
