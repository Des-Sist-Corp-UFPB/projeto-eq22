import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { ApiError } from "@/lib/api/client";
import { SceneAiAnalysisPanel } from "@/features/scenes/components/scene-ai-analysis-panel";
import type { SceneAnalysisResult } from "@/features/scenes/api/analyze-scene";
import { renderWithClient } from "@/test/test-utils";

const mocks = vi.hoisted(() => ({
  analyzeScene: vi.fn(),
}));

vi.mock("@/features/scenes/api/analyze-scene", () => ({
  analyzeScene: mocks.analyzeScene,
}));

const analysisResult: SceneAnalysisResult = {
  summary: "A protagonista encontra uma pista decisiva.",
  tone: "Tenso e introspectivo",
  pacing: "Ritmo crescente",
  strengths: ["Conflito bem definido"],
  issues: ["A reação termina cedo demais"],
  suggestions: ["Alongar o momento da descoberta"],
};

describe("SceneAiAnalysisPanel", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.analyzeScene.mockResolvedValue(analysisResult);
  });

  test("renderiza as seis seções de uma análise concluída", async () => {
    renderPanel();
    submitAnalysis();

    for (const heading of ["Resumo", "Tom", "Ritmo", "Pontos fortes", "Problemas encontrados", "Sugestões"]) {
      expect(await screen.findByRole("heading", { name: heading })).toBeInTheDocument();
    }
    expect(screen.getByText(analysisResult.summary)).toBeInTheDocument();
    expect(screen.getByText(analysisResult.strengths[0])).toBeInTheDocument();
  });

  test("apara e inclui foco não vazio", async () => {
    renderPanel();
    fireEvent.change(screen.getByLabelText("Foco da análise"), { target: { value: "  diálogos  " } });
    submitAnalysis();

    await waitFor(() => {
      expect(mocks.analyzeScene).toHaveBeenCalledWith("scene-1", { focus: "diálogos" }, expect.any(AbortSignal));
    });
  });

  test("omite foco em branco", async () => {
    renderPanel();
    fireEvent.change(screen.getByLabelText("Foco da análise"), { target: { value: "   " } });
    submitAnalysis();

    await waitFor(() => {
      expect(mocks.analyzeScene).toHaveBeenCalledWith("scene-1", {}, expect.any(AbortSignal));
    });
  });

  test("impede requisições duplicadas enquanto analisa", () => {
    const pending = deferred<SceneAnalysisResult>();
    mocks.analyzeScene.mockReturnValue(pending.promise);
    renderPanel();
    const form = screen.getByRole("button", { name: "Analisar com IA" }).closest("form");

    fireEvent.submit(form as HTMLFormElement);
    fireEvent.submit(form as HTMLFormElement);

    expect(screen.getByRole("button", { name: "Analisando..." })).toBeDisabled();
    expect(mocks.analyzeScene).toHaveBeenCalledTimes(1);
  });

  test("mostra mensagem específica para HTTP 503", async () => {
    mocks.analyzeScene.mockRejectedValue(new ApiError("unavailable", 503));
    renderPanel();
    submitAnalysis();

    expect(
      await screen.findByText("A análise com IA está indisponível no momento. Tente novamente mais tarde.")
    ).toBeInTheDocument();
  });

  test("mostra mensagem segura para outras falhas", async () => {
    mocks.analyzeScene.mockRejectedValue(new Error("provider details"));
    renderPanel();
    submitAnalysis();

    expect(await screen.findByText("Não foi possível concluir a análise. Tente novamente.")).toBeInTheDocument();
  });

  test("limpa um resultado concluído ao trocar de cena", async () => {
    const { rerender } = renderPanel();
    submitAnalysis();
    expect(await screen.findByText(analysisResult.summary)).toBeInTheDocument();

    rerender(<SceneAiAnalysisPanel sceneId="scene-2" />);

    await waitFor(() => {
      expect(screen.queryByText(analysisResult.summary)).not.toBeInTheDocument();
    });
  });

  test("ignora sucesso atrasado da cena anterior", async () => {
    const pending = deferred<SceneAnalysisResult>();
    mocks.analyzeScene.mockReturnValue(pending.promise);
    const { rerender } = renderPanel();
    submitAnalysis();

    rerender(<SceneAiAnalysisPanel sceneId="scene-2" />);
    await act(async () => pending.resolve(analysisResult));

    expect(screen.queryByText(analysisResult.summary)).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  test.each([
    ["503", new ApiError("unavailable", 503)],
    ["genérica", new Error("provider details")],
  ])("ignora falha %s atrasada da cena anterior", async (_label, failure) => {
    const pending = deferred<SceneAnalysisResult>();
    mocks.analyzeScene.mockReturnValue(pending.promise);
    const { rerender } = renderPanel();
    submitAnalysis();

    rerender(<SceneAiAnalysisPanel sceneId="scene-2" />);
    await act(async () => pending.reject(failure));

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  test("mantém AbortError silencioso", async () => {
    mocks.analyzeScene.mockRejectedValue(new DOMException("Aborted", "AbortError"));
    renderPanel();
    submitAnalysis();

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled();
    });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  test("entrega um AbortSignal ativo à requisição", async () => {
    renderPanel();
    submitAnalysis();

    await waitFor(() => expect(mocks.analyzeScene).toHaveBeenCalled());
    const signal = mocks.analyzeScene.mock.calls[0][2] as AbortSignal;
    expect(signal).toBeInstanceOf(AbortSignal);
    expect(signal.aborted).toBe(false);
  });

  test("aborta a requisição anterior ao trocar de cena", async () => {
    const pending = deferred<SceneAnalysisResult>();
    mocks.analyzeScene.mockReturnValue(pending.promise);
    const { rerender } = renderPanel();
    submitAnalysis();
    const signal = mocks.analyzeScene.mock.calls[0][2] as AbortSignal;

    rerender(<SceneAiAnalysisPanel sceneId="scene-2" />);

    await waitFor(() => expect(signal.aborted).toBe(true));
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled();
  });
});

function renderPanel() {
  const renderResult = renderWithClient(<SceneAiAnalysisPanel sceneId="scene-1" />);
  fireEvent.click(screen.getByRole("button", { name: "Expandir análise com IA" }));
  return renderResult;
}

function submitAnalysis() {
  fireEvent.click(screen.getByRole("button", { name: "Analisar com IA" }));
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}
