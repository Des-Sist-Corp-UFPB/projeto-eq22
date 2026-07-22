import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, expect, test, vi } from "vitest";
import { SceneVersionHistoryPanel } from "@/features/scenes/components/scene-version-history-panel";
import type { SceneVersionPage } from "@/features/scenes/types";
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
  apiMocks.getSceneVersion.mockResolvedValue(versionDetail("version-1", "Texto antigo completo"));
});

test("load more requests the next history page and appends older versions", async () => {
  apiMocks.listSceneVersions.mockImplementation((_sceneId: string, page = 0) =>
    Promise.resolve(page === 0 ? versionPage("version-1", "Texto recente", true, 0) : versionPage("version-2", "Texto antigo", false, 1))
  );

  renderPanel();

  expect(await screen.findByText("Texto recente")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Carregar versoes anteriores" }));

  expect(await screen.findByText("Texto antigo")).toBeInTheDocument();
  expect(screen.getByText("Texto recente")).toBeInTheDocument();
  expect(apiMocks.listSceneVersions).toHaveBeenCalledWith(sceneForPlanning.id, 1);
});

test("load more is hidden when the first history page has no next page", async () => {
  apiMocks.listSceneVersions.mockResolvedValue(versionPage("version-1", "Texto unico", false, 0));

  renderPanel();

  expect(await screen.findByText("Texto unico")).toBeInTheDocument();
  await waitFor(() => {
    expect(screen.queryByRole("button", { name: "Carregar versoes anteriores" })).not.toBeInTheDocument();
  });
});

function renderPanel() {
  renderWithClient(
    <SceneVersionHistoryPanel
      sceneId={sceneForPlanning.id}
      hasUnsavedContent={false}
      contentSaveInFlight={false}
      restoreDisabled={false}
      restorePending={false}
      restoreError={null}
      onClose={vi.fn()}
      onRestoreVersion={vi.fn()}
    />
  );
}

function versionPage(versionId: string, preview: string, hasNext: boolean, page: number): SceneVersionPage {
  return {
    items: [
      {
        id: versionId,
        sceneId: sceneForPlanning.id,
        originalSceneId: sceneForPlanning.id,
        sceneTitleSnapshot: sceneForPlanning.title,
        wordCount: 2,
        source: "MANUAL_SAVE",
        createdAt: "2026-06-08T10:00:00Z",
        contentTextPreview: preview,
      },
    ],
    page,
    size: 20,
    hasNext,
  };
}

function versionDetail(versionId: string, contentText: string) {
  return {
    id: versionId,
    sceneId: sceneForPlanning.id,
    originalSceneId: sceneForPlanning.id,
    sceneTitleSnapshot: sceneForPlanning.title,
    wordCount: 2,
    source: "MANUAL_SAVE" as const,
    createdAt: "2026-06-08T10:00:00Z",
    contentTextPreview: contentText,
    contentJson: "{}",
    contentText,
  };
}
