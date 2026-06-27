import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import React from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { SceneEditor } from "@/features/scenes/components/scene-editor";
import { sceneForPlanning } from "@/test/fixtures";
import { renderWithClient } from "@/test/test-utils";

const mocks = vi.hoisted(() => ({
  getScene: vi.fn(),
  updateScene: vi.fn(),
  updateSceneContent: vi.fn(),
  restoreSceneVersion: vi.fn(),
  deleteScene: vi.fn(),
  analyzeScene: vi.fn(),
  randomUUID: vi.fn(),
}));

vi.mock("@/features/scenes/api/scenes-api", () => ({
  getScene: mocks.getScene,
  updateScene: mocks.updateScene,
  updateSceneContent: mocks.updateSceneContent,
  restoreSceneVersion: mocks.restoreSceneVersion,
  deleteScene: mocks.deleteScene,
}));

vi.mock("@/features/scenes/api/analyze-scene", () => ({
  analyzeScene: mocks.analyzeScene,
}));

vi.mock("@/features/scenes/components/scene-content-editor", () => ({
  SceneContentEditor: ({
    sourceSceneId,
    onContentChange,
  }: {
    sourceSceneId: string;
    onContentChange: (sceneId: string, contentJson: string, contentText: string) => void;
  }) => (
    <button type="button" onClick={() => onContentChange(sourceSceneId, "{\"type\":\"doc\"}", "Novo texto")}>
      Alterar conteudo
    </button>
  ),
}));

vi.mock("@/features/scenes/components/scene-version-history-panel", () => ({
  SceneVersionHistoryPanel: () => <div />,
}));

const analysisResult = {
  summary: "Resumo da análise",
  tone: "Tenso",
  pacing: "Crescente",
  strengths: ["Conflito claro"],
  issues: ["Transição rápida"],
  suggestions: ["Expandir a reação"],
};

describe("SceneEditor content save contract", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.randomUUID
      .mockReturnValueOnce("operation-autosave")
      .mockReturnValueOnce("operation-manual")
      .mockReturnValueOnce("operation-first")
      .mockReturnValueOnce("operation-second");
    vi.stubGlobal("crypto", {
      randomUUID: mocks.randomUUID,
    });
    mocks.getScene.mockResolvedValue({ ...sceneForPlanning, contentRevision: 3 });
    mocks.updateScene.mockResolvedValue(sceneForPlanning);
    mocks.updateSceneContent.mockResolvedValue({ ...sceneForPlanning, contentText: "Novo texto", contentRevision: 4 });
    mocks.restoreSceneVersion.mockResolvedValue(sceneForPlanning);
    mocks.deleteScene.mockResolvedValue(undefined);
    mocks.analyzeScene.mockResolvedValue(analysisResult);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  test("autosave sends expectedContentRevision operationId and AUTO_SAVE", async () => {
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    await act(async () => {
      vi.advanceTimersByTime(1200);
    });
    vi.useRealTimers();

    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenCalledWith(sceneForPlanning.id, {
        contentJson: "{\"type\":\"doc\"}",
        contentText: "Novo texto",
        source: "AUTO_SAVE",
        expectedContentRevision: 3,
        operationId: "operation-autosave",
      });
    });
  });

  test("manual save sends expectedContentRevision operationId and MANUAL_SAVE", async () => {
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    fireEvent.click(screen.getByRole("button", { name: /Salvar conte.do/ }));

    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenCalledWith(sceneForPlanning.id, {
        contentJson: sceneForPlanning.contentJson,
        contentText: sceneForPlanning.contentText,
        source: "MANUAL_SAVE",
        expectedContentRevision: 3,
        operationId: "operation-manual",
      });
    });
  });

  test("successful response updates revision refs for later saves", async () => {
    mocks.updateSceneContent
      .mockResolvedValueOnce({ ...sceneForPlanning, contentRevision: 4 })
      .mockResolvedValueOnce({ ...sceneForPlanning, contentRevision: 5 });
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    fireEvent.click(screen.getByRole("button", { name: /Salvar conte.do/ }));
    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenCalledTimes(1);
    });

    fireEvent.click(screen.getByRole("button", { name: /Salvar conte.do/ }));

    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenLastCalledWith(
        sceneForPlanning.id,
        expect.objectContaining({
          expectedContentRevision: 4,
          operationId: "operation-second",
        })
      );
    });
  });

  test("failed autosave uses one operationId and is not retried by the editor", async () => {
    mocks.updateSceneContent.mockRejectedValueOnce(new Error("network down"));
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    await act(async () => {
      vi.advanceTimersByTime(1200);
    });
    vi.useRealTimers();

    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenCalledTimes(1);
    });
    expect(mocks.randomUUID).toHaveBeenCalledTimes(1);
    expect(mocks.updateSceneContent).toHaveBeenCalledWith(sceneForPlanning.id, expect.objectContaining({
      operationId: "operation-autosave",
    }));
  });

  test("failed content save disables AI analysis", async () => {
    mocks.updateSceneContent.mockRejectedValueOnce(new Error("network down"));
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    fireEvent.click(screen.getByRole("button", { name: "Expandir análise com IA" }));
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    fireEvent.click(screen.getByRole("button", { name: "Salvar conteúdo" }));

    expect(
      await screen.findByText("O conteúdo mais recente não foi salvo. Salve novamente antes de analisar.")
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeDisabled();
    expect(mocks.analyzeScene).not.toHaveBeenCalled();
  });

  test("AI analysis follows dirty saving and saved content transitions", async () => {
    const inFlightSave = createDeferred<typeof sceneForPlanning>();
    mocks.updateSceneContent.mockReturnValueOnce(inFlightSave.promise);
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    fireEvent.click(screen.getByRole("button", { name: "Expandir análise com IA" }));
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled();

    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeDisabled();
    expect(screen.getByText("Salve as alterações antes de analisar a versão mais recente.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Salvar conteúdo" }));

    await waitFor(() => expect(mocks.updateSceneContent).toHaveBeenCalledTimes(1));
    expect(await screen.findByText("O conteúdo está sendo salvo. Aguarde para analisar.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeDisabled();

    await act(async () => {
      inFlightSave.resolve({
        ...sceneForPlanning,
        contentJson: "{\"type\":\"doc\"}",
        contentText: "Novo texto",
        contentRevision: 4,
      });
      await inFlightSave.promise;
    });

    await waitFor(() => expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeEnabled());
    expect(screen.getByText("A IA analisa a última versão salva.")).toBeInTheDocument();
    expect(mocks.analyzeScene).not.toHaveBeenCalled();
    expect(mocks.updateScene).not.toHaveBeenCalled();
  });

  test("editing aborts an in-flight analysis and ignores its response", async () => {
    const pendingAnalysis = createDeferred<typeof analysisResult>();
    mocks.analyzeScene.mockReturnValueOnce(pendingAnalysis.promise);
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    fireEvent.click(screen.getByRole("button", { name: "Expandir análise com IA" }));
    fireEvent.click(screen.getByRole("button", { name: "Analisar com IA" }));
    const signal = mocks.analyzeScene.mock.calls[0][2] as AbortSignal;

    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));

    expect(signal.aborted).toBe(true);
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeDisabled();
    await act(async () => pendingAnalysis.resolve(analysisResult));
    expect(screen.queryByText(analysisResult.summary)).not.toBeInTheDocument();
    expect(mocks.updateSceneContent).not.toHaveBeenCalled();
    vi.useRealTimers();
  });

  test("editing after a successful analysis clears the result without saving immediately", async () => {
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });
    fireEvent.click(screen.getByRole("button", { name: "Expandir análise com IA" }));
    fireEvent.click(screen.getByRole("button", { name: "Analisar com IA" }));
    expect(await screen.findByText(analysisResult.summary)).toBeInTheDocument();

    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));

    expect(screen.queryByText(analysisResult.summary)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Analisar com IA" })).toBeDisabled();
    expect(mocks.updateSceneContent).not.toHaveBeenCalled();
    vi.useRealTimers();
  });

  test("AI analysis does not save metadata or content and does not schedule autosave", async () => {
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    fireEvent.click(screen.getByRole("button", { name: "Expandir análise com IA" }));
    fireEvent.click(screen.getByRole("button", { name: "Analisar com IA" }));

    expect(await screen.findByText("Resumo da análise")).toBeInTheDocument();
    expect(mocks.analyzeScene).toHaveBeenCalledWith(sceneForPlanning.id, {}, expect.any(AbortSignal));

    vi.useFakeTimers();
    await act(async () => {
      vi.advanceTimersByTime(1200);
    });
    vi.useRealTimers();

    expect(mocks.updateScene).not.toHaveBeenCalled();
    expect(mocks.updateSceneContent).not.toHaveBeenCalled();
  });
});

function renderEditor() {
  renderWithClient(
    <SceneEditor
      bookId="book-1"
      sceneId={sceneForPlanning.id}
      onSceneDeleted={vi.fn()}
    />
  );
}

function createDeferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, resolve, reject };
}
