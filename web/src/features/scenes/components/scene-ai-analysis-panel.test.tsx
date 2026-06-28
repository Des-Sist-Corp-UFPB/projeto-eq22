import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { ApiError } from "@/lib/api/client";
import {
  SceneAiAnalysisPanel,
  type SceneContentSyncState,
} from "@/features/scenes/components/scene-ai-analysis-panel";
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

  test("expõe limite de 300 caracteres e associa o texto auxiliar", () => {
    renderPanel();
    const textarea = screen.getByLabelText("Foco da análise");

    expect(textarea).toHaveAttribute("maxlength", "300");
    expect(textarea).toHaveAttribute("aria-describedby", "scene-ai-analysis-focus-help");
    expect(screen.getByText("0/300")).toBeInTheDocument();
  });

  test("atualiza o contador de caracteres", () => {
    renderPanel();

    fireEvent.change(screen.getByLabelText("Foco da análise"), { target: { value: "a".repeat(42) } });

    expect(screen.getByText("42/300")).toBeInTheDocument();
  });

  test("aceita 300 caracteres e impede foco submetido acima do limite", async () => {
    renderPanel();
    const textarea = screen.getByLabelText("Foco da análise");
    const validFocus = "a".repeat(300);

    fireEvent.change(textarea, { target: { value: validFocus } });
    fireEvent.change(textarea, { target: { value: `${validFocus}b` } });
    submitAnalysis();

    expect(textarea).toHaveValue(validFocus);
    await waitFor(() => {
      expect(mocks.analyzeScene).toHaveBeenCalledWith(
        "scene-1",
        { focus: validFocus },
        expect.any(AbortSignal)
      );
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

  test.each([
    [
      "HTTP 503",
      new ApiError("unavailable", 503),
      "A análise com IA está indisponível no momento. Tente novamente mais tarde.",
    ],
    ["falha genérica", new Error("provider details"), "Não foi possível concluir a análise. Tente novamente."],
  ])("remove o resultado anterior quando uma nova análise termina com %s", async (_label, failure, message) => {
    const rerun = deferred<SceneAnalysisResult>();
    mocks.analyzeScene
      .mockResolvedValueOnce(analysisResult)
      .mockReturnValueOnce(rerun.promise);
    renderPanel();
    submitAnalysis();
    expect(await screen.findByText(analysisResult.summary)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled());

    submitAnalysis();

    expect(screen.queryByText(analysisResult.summary)).not.toBeInTheDocument();
    await act(async () => rerun.reject(failure));
    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.queryByText(analysisResult.summary)).not.toBeInTheDocument();
  });

  test.each(
    [
      ["dirty", "Salve as alterações antes de analisar a versão mais recente."],
      ["saving", "O conteúdo está sendo salvo. Aguarde para analisar."],
      ["error", "O conteúdo mais recente não foi salvo. Salve novamente antes de analisar."],
      ["loading", "Aguarde o carregamento do conteúdo da cena."],
    ] satisfies Array<[SceneContentSyncState, string]>
  )("desabilita análise quando o conteúdo está %s", (contentSyncState, message) => {
    renderPanel(contentSyncState);

    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeDisabled();
    expect(screen.getByText(message)).toBeInTheDocument();
    expect(mocks.analyzeScene).not.toHaveBeenCalled();
  });

  test("habilita análise para conteúdo salvo e explica a versão analisada", () => {
    renderPanel("saved");

    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled();
    expect(screen.getByText("A IA analisa a última versão salva.")).toBeInTheDocument();
  });

  test("disables analysis when a newer remote revision is pending", () => {
    renderPanel("outdated");

    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeDisabled();
    expect(screen.getByText(/versão salva mais recente/)).toBeInTheDocument();
    expect(mocks.analyzeScene).not.toHaveBeenCalled();
  });

  test("clears a completed analysis when the accepted revision changes and preserves focus", async () => {
    const { rerender } = renderPanel("saved", 1);
    fireEvent.change(screen.getByLabelText(/Foco/), { target: { value: "ritmo" } });
    submitAnalysis();
    expect(await screen.findByText(analysisResult.summary)).toBeInTheDocument();

    rerender(<SceneAiAnalysisPanel sceneId="scene-1" contentRevision={2} contentSyncState="saved" />);

    await waitFor(() => expect(screen.queryByText(analysisResult.summary)).not.toBeInTheDocument());
    expect(screen.getByLabelText(/Foco/)).toHaveValue("ritmo");
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled();
  });

  test("clears focus on scene change and does not send the previous scene focus", async () => {
    const { rerender } = renderPanel("saved", 1);
    fireEvent.change(screen.getByLabelText(/Foco/), { target: { value: "ritmo" } });

    rerender(<SceneAiAnalysisPanel sceneId="scene-2" contentRevision={1} contentSyncState="saved" />);

    await waitFor(() => expect(screen.getByLabelText(/Foco/)).toHaveValue(""));
    submitAnalysis();
    await waitFor(() => {
      expect(mocks.analyzeScene).toHaveBeenCalledWith("scene-2", {}, expect.any(AbortSignal));
    });
  });

  test("aborts and ignores pending analysis when the accepted revision changes", async () => {
    const pending = deferred<SceneAnalysisResult>();
    mocks.analyzeScene.mockReturnValueOnce(pending.promise);
    const { rerender } = renderPanel("saved", 1);
    fireEvent.change(screen.getByLabelText(/Foco/), { target: { value: "diálogo" } });
    submitAnalysis();
    const signal = mocks.analyzeScene.mock.calls[0][2] as AbortSignal;

    rerender(<SceneAiAnalysisPanel sceneId="scene-1" contentRevision={2} contentSyncState="saved" />);

    await waitFor(() => expect(signal.aborted).toBe(true));
    expect(screen.getByLabelText(/Foco/)).toHaveValue("diálogo");
    await act(async () => pending.resolve(analysisResult));
    expect(screen.queryByText(analysisResult.summary)).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  test.each(["dirty", "saving", "error", "loading", "outdated"] satisfies SceneContentSyncState[])(
    "aborta e ignora resposta pendente quando o conteúdo muda para %s",
    async (contentSyncState) => {
      const pending = deferred<SceneAnalysisResult>();
      mocks.analyzeScene.mockReturnValue(pending.promise);
      const { rerender } = renderPanel();
      fireEvent.change(screen.getByLabelText("Foco da análise"), { target: { value: "ritmo" } });
      submitAnalysis();
      const signal = mocks.analyzeScene.mock.calls[0][2] as AbortSignal;

      rerender(<SceneAiAnalysisPanel sceneId="scene-1" contentRevision={1} contentSyncState={contentSyncState} />);

      await waitFor(() => expect(signal.aborted).toBe(true));
      expect(screen.getByLabelText("Foco da análise")).toHaveValue("ritmo");
      await act(async () => pending.resolve(analysisResult));
      expect(screen.queryByText(analysisResult.summary)).not.toBeInTheDocument();
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    }
  );

  test("limpa resultado quando o conteúdo deixa de estar salvo", async () => {
    const { rerender } = renderPanel();
    submitAnalysis();
    expect(await screen.findByText(analysisResult.summary)).toBeInTheDocument();

    rerender(<SceneAiAnalysisPanel sceneId="scene-1" contentRevision={1} contentSyncState="dirty" />);

    await waitFor(() => expect(screen.queryByText(analysisResult.summary)).not.toBeInTheDocument());
  });

  test("limpa erro quando o conteúdo deixa de estar salvo", async () => {
    mocks.analyzeScene.mockRejectedValue(new Error("provider details"));
    const { rerender } = renderPanel();
    submitAnalysis();
    expect(await screen.findByRole("alert")).toBeInTheDocument();

    rerender(<SceneAiAnalysisPanel sceneId="scene-1" contentRevision={1} contentSyncState="dirty" />);

    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
  });

  test("volta a habilitar análise quando o conteúdo retorna a salvo", () => {
    const { rerender } = renderPanel("dirty");
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeDisabled();

    rerender(<SceneAiAnalysisPanel sceneId="scene-1" contentRevision={1} contentSyncState="saved" />);

    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled();
  });

  test("limpa um resultado concluído ao trocar de cena", async () => {
    const { rerender } = renderPanel();
    submitAnalysis();
    expect(await screen.findByText(analysisResult.summary)).toBeInTheDocument();

    rerender(<SceneAiAnalysisPanel sceneId="scene-2" contentRevision={1} contentSyncState="saved" />);

    await waitFor(() => {
      expect(screen.queryByText(analysisResult.summary)).not.toBeInTheDocument();
    });
  });

  test("ignora sucesso atrasado da cena anterior", async () => {
    const pending = deferred<SceneAnalysisResult>();
    mocks.analyzeScene.mockReturnValue(pending.promise);
    const { rerender } = renderPanel();
    submitAnalysis();

    rerender(<SceneAiAnalysisPanel sceneId="scene-2" contentRevision={1} contentSyncState="saved" />);
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

    rerender(<SceneAiAnalysisPanel sceneId="scene-2" contentRevision={1} contentSyncState="saved" />);
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

    rerender(<SceneAiAnalysisPanel sceneId="scene-2" contentRevision={1} contentSyncState="saved" />);

    await waitFor(() => expect(signal.aborted).toBe(true));
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled();
  });
});

function renderPanel(contentSyncState: SceneContentSyncState = "saved", contentRevision = 1) {
  const renderResult = renderWithClient(
    <SceneAiAnalysisPanel
      sceneId="scene-1"
      contentRevision={contentRevision}
      contentSyncState={contentSyncState}
    />
  );
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
