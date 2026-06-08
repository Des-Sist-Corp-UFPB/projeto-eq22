import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, expect, test, vi } from "vitest";
import { SceneVersionHistoryPanel } from "@/features/scenes/components/scene-version-history-panel";
import type { Scene } from "@/features/scenes/types";
import { sceneForPlanning } from "@/test/fixtures";
import { renderWithClient } from "@/test/test-utils";

const apiMocks = vi.hoisted(() => ({
  listSceneVersions: vi.fn(),
  getSceneVersion: vi.fn(),
  restoreSceneVersion: vi.fn(),
}));

vi.mock("@/features/scenes/api/scenes-api", () => apiMocks);

beforeEach(() => {
  vi.restoreAllMocks();
  apiMocks.listSceneVersions.mockReset();
  apiMocks.getSceneVersion.mockReset();
  apiMocks.restoreSceneVersion.mockReset();
});

test("mostra versoes, carrega previa e restaura com confirmacao", async () => {
  const restoredScene: Scene = {
    ...sceneForPlanning,
    contentText: "Texto restaurado",
    contentRevision: 3,
  };
  const onRestored = vi.fn();
  apiMocks.listSceneVersions.mockResolvedValue({
    items: [
      {
        id: "version-1",
        sceneId: sceneForPlanning.id,
        originalSceneId: sceneForPlanning.id,
        sceneTitleSnapshot: sceneForPlanning.title,
        wordCount: 2,
        source: "MANUAL_SAVE",
        createdAt: "2026-06-08T10:00:00Z",
        contentTextPreview: "Texto antigo",
      },
    ],
    page: 0,
    size: 20,
    hasNext: false,
  });
  apiMocks.getSceneVersion.mockResolvedValue({
    id: "version-1",
    sceneId: sceneForPlanning.id,
    originalSceneId: sceneForPlanning.id,
    sceneTitleSnapshot: sceneForPlanning.title,
    wordCount: 2,
    source: "MANUAL_SAVE",
    createdAt: "2026-06-08T10:00:00Z",
    contentTextPreview: "Texto antigo",
    contentJson: "{}",
    contentText: "Texto antigo completo",
  });
  apiMocks.restoreSceneVersion.mockResolvedValue(restoredScene);
  vi.spyOn(window, "confirm").mockReturnValue(true);

  renderWithClient(
    <SceneVersionHistoryPanel
      sceneId={sceneForPlanning.id}
      expectedContentRevision={2}
      restoreDisabled={false}
      onClose={vi.fn()}
      onRestored={onRestored}
    />
  );

  expect(await screen.findByText("Antes do salvamento manual")).toBeInTheDocument();
  expect(await screen.findByText("Texto antigo completo")).toBeInTheDocument();

  fireEvent.click(screen.getByRole("button", { name: "Restaurar versão" }));

  await waitFor(() => {
    expect(apiMocks.restoreSceneVersion).toHaveBeenCalledWith(sceneForPlanning.id, "version-1", 2);
    expect(onRestored).toHaveBeenCalledWith(restoredScene);
  });
});
