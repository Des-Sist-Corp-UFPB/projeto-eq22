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
    mocks.analyzeScene.mockResolvedValue({
      summary: "Resumo da análise",
      tone: "Tenso",
      pacing: "Crescente",
      strengths: ["Conflito claro"],
      issues: ["Transição rápida"],
      suggestions: ["Expandir a reação"],
    });
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
