import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import React from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { SceneEditor } from "@/features/scenes/components/scene-editor";
import type { SceneVersionRestoreMode } from "@/features/scenes/components/scene-version-history-panel";
import { sceneForPlanning } from "@/test/fixtures";
import { renderWithClient } from "@/test/test-utils";

const mocks = vi.hoisted(() => ({
  getScene: vi.fn(),
  updateScene: vi.fn(),
  updateSceneContent: vi.fn(),
  restoreSceneVersion: vi.fn(),
  deleteScene: vi.fn(),
  randomUUID: vi.fn(),
}));

vi.mock("@/features/scenes/api/scenes-api", () => ({
  getScene: mocks.getScene,
  updateScene: mocks.updateScene,
  updateSceneContent: mocks.updateSceneContent,
  restoreSceneVersion: mocks.restoreSceneVersion,
  deleteScene: mocks.deleteScene,
}));

vi.mock("@/features/scenes/components/scene-content-editor", () => ({
  SceneContentEditor: ({
    sourceSceneId,
    contentText,
    onContentChange,
  }: {
    sourceSceneId: string;
    contentText: string;
    onContentChange: (sceneId: string, contentJson: string, contentText: string) => void;
  }) => (
    <div>
      <p data-testid="editor-content">{contentText}</p>
      <button type="button" onClick={() => onContentChange(sourceSceneId, "{\"type\":\"doc\"}", "Novo texto")}>
        Alterar conteudo
      </button>
    </div>
  ),
}));

vi.mock("@/features/scenes/components/scene-version-history-panel", () => ({
  SceneVersionHistoryPanel: ({
    onRestoreVersion,
  }: {
    onRestoreVersion: (versionId: string, mode: SceneVersionRestoreMode) => Promise<void>;
  }) => (
    <div>
      <button type="button" onClick={() => void onRestoreVersion("version-1", "RESTORE")}>
        Restore clean
      </button>
      <button type="button" onClick={() => void onRestoreVersion("version-1", "SAVE_AND_RESTORE")}>
        Save and restore
      </button>
      <button type="button" onClick={() => void onRestoreVersion("version-1", "DISCARD_AND_RESTORE")}>
        Discard and restore
      </button>
    </div>
  ),
}));

describe("SceneEditor restore orchestration", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.randomUUID
      .mockReturnValueOnce("save-operation")
      .mockReturnValueOnce("restore-operation")
      .mockReturnValue("later-operation");
    vi.stubGlobal("crypto", {
      randomUUID: mocks.randomUUID,
    });
    mocks.getScene.mockResolvedValue({ ...sceneForPlanning, contentRevision: 3 });
    mocks.updateScene.mockResolvedValue(sceneForPlanning);
    mocks.updateSceneContent.mockResolvedValue({ ...sceneForPlanning, contentText: "Novo texto", contentRevision: 4 });
    mocks.restoreSceneVersion.mockResolvedValue({ ...sceneForPlanning, contentText: "Texto restaurado", contentRevision: 8 });
    mocks.deleteScene.mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  test("queued autosave is cancelled before restore", async () => {
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    openHistory();
    fireEvent.click(screen.getByRole("button", { name: "Restore clean" }));
    await act(async () => {
      await Promise.resolve();
    });
    expect(mocks.restoreSceneVersion).toHaveBeenCalledTimes(1);

    await act(async () => {
      vi.advanceTimersByTime(1200);
    });
    vi.useRealTimers();

    expect(mocks.updateSceneContent).not.toHaveBeenCalled();
  });

  test("stale autosave callback cannot overwrite restored content", async () => {
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    openHistory();
    fireEvent.click(screen.getByRole("button", { name: "Restore clean" }));
    await act(async () => {
      await Promise.resolve();
    });
    expect(screen.getByTestId("editor-content")).toHaveTextContent("Texto restaurado");

    await act(async () => {
      vi.advanceTimersByTime(1200);
    });
    vi.useRealTimers();

    expect(screen.getByTestId("editor-content")).toHaveTextContent("Texto restaurado");
    expect(mocks.updateSceneContent).not.toHaveBeenCalled();
  });

  test("save and restore saves first and restores with returned revision", async () => {
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    openHistory();
    fireEvent.click(screen.getByRole("button", { name: "Save and restore" }));

    await waitFor(() => {
      expect(mocks.updateSceneContent).toHaveBeenCalledWith(sceneForPlanning.id, expect.objectContaining({
        source: "MANUAL_SAVE",
        expectedContentRevision: 3,
        operationId: "save-operation",
      }));
      expect(mocks.restoreSceneVersion).toHaveBeenCalledWith(sceneForPlanning.id, "version-1", {
        expectedContentRevision: 4,
        operationId: "restore-operation",
      });
    });
  });

  test("discard and restore keeps local content until explicit discard confirmation", async () => {
    renderEditor();
    await screen.findByRole("heading", { name: sceneForPlanning.title });

    fireEvent.click(screen.getByRole("button", { name: "Alterar conteudo" }));
    openHistory();

    expect(screen.getByTestId("editor-content")).toHaveTextContent("Novo texto");
    expect(mocks.updateSceneContent).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Discard and restore" }));

    await waitFor(() => {
      expect(mocks.updateSceneContent).not.toHaveBeenCalled();
      expect(mocks.restoreSceneVersion).toHaveBeenCalledWith(sceneForPlanning.id, "version-1", {
        expectedContentRevision: 3,
        operationId: "save-operation",
      });
    });
  });
});

function openHistory() {
  fireEvent.click(screen.getByRole("button", { name: /Hist/ }));
}

function renderEditor() {
  renderWithClient(
    <SceneEditor
      bookId="book-1"
      sceneId={sceneForPlanning.id}
      onSceneDeleted={vi.fn()}
    />
  );
}
