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
  deleteScene: vi.fn(),
  randomUUID: vi.fn(),
}));

vi.mock("@/features/scenes/api/scenes-api", () => ({
  getScene: mocks.getScene,
  updateScene: mocks.updateScene,
  updateSceneContent: mocks.updateSceneContent,
  deleteScene: mocks.deleteScene,
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
    mocks.deleteScene.mockResolvedValue(undefined);
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
