import type { BookOutline, OutlineScene } from "@/features/outline/types";
import type { SceneStatus } from "@/features/scenes/types";

export const KANBAN_STATUS_COLUMNS: { status: SceneStatus; label: string }[] = [
  { status: "IDEA", label: "Ideia" },
  { status: "PLANNED", label: "Planejada" },
  { status: "DRAFT", label: "Rascunho" },
  { status: "WRITTEN", label: "Escrita" },
  { status: "REVISED", label: "Revisada" },
  { status: "FINAL", label: "Final" },
];

const ADVANCED_STATUSES = new Set<SceneStatus>(["DRAFT", "WRITTEN", "REVISED", "FINAL"]);

export type KanbanSceneCardModel = {
  id: string;
  scene: OutlineScene;
  status: SceneStatus;
  chapterTitle: string;
  sectionTitle: string;
  manuscriptIndex: number;
};

export type KanbanColumnModel = {
  status: SceneStatus;
  label: string;
  sceneCount: number;
  wordCount: number;
  scenes: KanbanSceneCardModel[];
};

export type KanbanModel = {
  columns: KanbanColumnModel[];
  scenes: KanbanSceneCardModel[];
};

export type KanbanTransitionDecision =
  | { type: "noop" }
  | { type: "allowed" }
  | { type: "blocked-planned" }
  | { type: "confirm-advanced" };

export type KanbanStatusOverrides = Partial<Record<string, SceneStatus>>;

export function buildKanbanModel(
  outline: BookOutline,
  statusOverrides: KanbanStatusOverrides = {}
): KanbanModel {
  const scenes: KanbanSceneCardModel[] = [];
  let manuscriptIndex = 0;

  for (const section of [...outline.sections].sort(compareBySortOrder)) {
    for (const chapter of [...section.chapters].sort(compareBySortOrder)) {
      for (const scene of [...chapter.scenes].sort(compareBySortOrder)) {
        const status = statusOverrides[scene.id] ?? scene.status;
        scenes.push({
          id: scene.id,
          scene,
          status,
          chapterTitle: chapter.title,
          sectionTitle: section.title,
          manuscriptIndex,
        });
        manuscriptIndex++;
      }
    }
  }

  const columns = KANBAN_STATUS_COLUMNS.map(({ status, label }) => {
    const columnScenes = scenes.filter((scene) => scene.status === status);
    return {
      status,
      label,
      sceneCount: columnScenes.length,
      wordCount: columnScenes.reduce((total, scene) => total + scene.scene.wordCount, 0),
      scenes: columnScenes,
    };
  });

  return { columns, scenes };
}

export function reconcileKanbanStatusOverrides(
  outline: BookOutline,
  statusOverrides: KanbanStatusOverrides,
  pendingSceneIds: ReadonlySet<string> = new Set()
): KanbanStatusOverrides {
  const serverStatuses = getOutlineSceneStatuses(outline);
  let didChange = false;
  const nextOverrides = { ...statusOverrides };

  for (const [sceneId] of Object.entries(statusOverrides)) {
    if (pendingSceneIds.has(sceneId)) {
      continue;
    }

    if (!serverStatuses.has(sceneId)) {
      delete nextOverrides[sceneId];
      didChange = true;
      continue;
    }

    // Fresh outline data is authoritative once there is no pending mutation for this scene.
    delete nextOverrides[sceneId];
    didChange = true;
  }

  return didChange ? nextOverrides : statusOverrides;
}

export function getKanbanTransitionDecision(
  scene: KanbanSceneCardModel,
  targetStatus: SceneStatus
): KanbanTransitionDecision {
  if (scene.status === targetStatus) {
    return { type: "noop" };
  }

  if (targetStatus === "IDEA") {
    return { type: "allowed" };
  }

  if (targetStatus === "PLANNED" && scene.scene.planningGaps.length > 0) {
    return { type: "blocked-planned" };
  }

  if (ADVANCED_STATUSES.has(targetStatus) && scene.scene.planningGaps.length > 0) {
    return { type: "confirm-advanced" };
  }

  return { type: "allowed" };
}

function compareBySortOrder<T extends { sortOrder: number }>(first: T, second: T) {
  return first.sortOrder - second.sortOrder;
}

function getOutlineSceneStatuses(outline: BookOutline) {
  const statuses = new Map<string, SceneStatus>();
  for (const section of outline.sections) {
    for (const chapter of section.chapters) {
      for (const scene of chapter.scenes) {
        statuses.set(scene.id, scene.status);
      }
    }
  }
  return statuses;
}
