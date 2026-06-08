import { fireEvent, screen } from "@testing-library/react";
import { beforeEach, expect, test, vi } from "vitest";
import { SceneVersionHistoryPanel } from "@/features/scenes/components/scene-version-history-panel";
import { sceneForPlanning } from "@/test/fixtures";
import { renderWithClient } from "@/test/test-utils";

const apiMocks = vi.hoisted(() => ({
  listSceneVersions: vi.fn(),
  getSceneVersion: vi.fn(),
}));

vi.mock("@/features/scenes/api/scenes-api", () => apiMocks);

beforeEach(() => {
  vi.restoreAllMocks();
  apiMocks.listSceneVersions.mockReset();
  apiMocks.getSceneVersion.mockReset();
});

test("dirty restore shows save discard and cancel actions", async () => {
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

  renderWithClient(
    <SceneVersionHistoryPanel
      sceneId={sceneForPlanning.id}
      hasUnsavedContent
      restoreDisabled={false}
      restorePending={false}
      restoreError={null}
      onClose={vi.fn()}
      onRestoreVersion={vi.fn()}
    />
  );

  expect(await screen.findByText("Salvamento manual")).toBeInTheDocument();
  expect(await screen.findByText("Texto antigo completo")).toBeInTheDocument();

  fireEvent.click(screen.getByRole("button", { name: "Restaurar versao" }));

  expect(screen.getByRole("button", { name: "Salvar alterações e restaurar" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Descartar alterações locais e restaurar" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Cancelar" })).toBeInTheDocument();
});
