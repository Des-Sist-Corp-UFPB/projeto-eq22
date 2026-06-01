"use client";

import { useMemo, useState } from "react";
import {
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  useDraggable,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragStartEvent,
  type DraggableAttributes,
  type DraggableSyntheticListeners,
} from "@dnd-kit/core";
import { CSS } from "@dnd-kit/utilities";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import type { CSSProperties, Ref } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import type { BookOutline } from "@/features/outline/types";
import { updateScene } from "@/features/scenes/api/scenes-api";
import type { SceneStatus } from "@/features/scenes/types";
import {
  buildKanbanModel,
  getKanbanTransitionDecision,
  KANBAN_STATUS_COLUMNS,
  type KanbanColumnModel,
  type KanbanSceneCardModel,
} from "@/features/kanban/utils/kanban-model";
import { ApiError } from "@/lib/api/client";
import { queryKeys } from "@/lib/query/keys";

type SceneKanbanPanelProps = {
  bookId: string;
  outline?: BookOutline | null;
  isLoading: boolean;
  isError: boolean;
  onOpenSceneInEditor: (sceneId: string) => void;
};

type PendingTransition = {
  kind: "blocked-planned" | "confirm-advanced";
  scene: KanbanSceneCardModel;
  targetStatus: SceneStatus;
};

export function SceneKanbanPanel({
  bookId,
  outline,
  isLoading,
  isError,
  onOpenSceneInEditor,
}: SceneKanbanPanelProps) {
  const queryClient = useQueryClient();
  const [statusOverrides, setStatusOverrides] = useState<Partial<Record<string, SceneStatus>>>({});
  const [pendingSceneId, setPendingSceneId] = useState<string | null>(null);
  const [activeSceneId, setActiveSceneId] = useState<string | null>(null);
  const [pendingTransition, setPendingTransition] = useState<PendingTransition | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const model = useMemo(
    () => (outline ? buildKanbanModel(outline, statusOverrides) : null),
    [outline, statusOverrides]
  );
  const activeScene = activeSceneId ? model?.scenes.find((scene) => scene.id === activeSceneId) ?? null : null;
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 8 } }), useSensor(KeyboardSensor));

  const mutation = useMutation({
    mutationFn: ({ sceneId, status }: { sceneId: string; status: SceneStatus }) => updateScene(sceneId, { status }),
    onSuccess: (scene) => {
      queryClient.setQueryData(queryKeys.scene(scene.id), scene);
      void queryClient.invalidateQueries({ queryKey: queryKeys.outline(bookId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.scene(scene.id) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.bookDashboard(bookId) });
    },
    onSettled: () => {
      setPendingSceneId(null);
    },
  });

  if (isLoading) {
    return (
      <section className="h-full overflow-y-auto bg-zinc-50 p-4 md:p-6">
        <LoadingState label="Carregando kanban..." />
      </section>
    );
  }

  if (isError) {
    return (
      <section className="h-full overflow-y-auto bg-zinc-50 p-4 md:p-6">
        <ErrorState message="Nao foi possivel carregar o kanban." />
      </section>
    );
  }

  if (!outline || !model) {
    return (
      <section className="h-full overflow-y-auto bg-zinc-50 p-4 md:p-6">
        <EmptyState title="Kanban indisponivel" description="Nao recebemos dados do outline deste livro." />
      </section>
    );
  }

  if (model.scenes.length === 0) {
    return (
      <section className="h-full overflow-y-auto bg-zinc-50 p-4 md:p-6">
        <div className="mx-auto grid max-w-6xl gap-4">
          <KanbanHeader />
          <EmptyState
            title="Este livro ainda nao tem cenas."
            description="Crie cenas na aba Cenas para acompanhar o fluxo de escrita no kanban."
          />
        </div>
      </section>
    );
  }

  function handleDragStart(event: DragStartEvent) {
    setActiveSceneId(String(event.active.id));
  }

  function clearActiveDrag() {
    setActiveSceneId(null);
  }

  function handleDragEnd(event: DragEndEvent) {
    clearActiveDrag();
    const targetStatus = event.over?.id as SceneStatus | undefined;
    if (!targetStatus || !isSceneStatus(targetStatus)) {
      return;
    }

    const scene = model?.scenes.find((candidate) => candidate.id === event.active.id);
    if (!scene) {
      return;
    }

    requestStatusChange(scene, targetStatus);
  }

  function requestStatusChange(scene: KanbanSceneCardModel, targetStatus: SceneStatus) {
    setErrorMessage(null);
    const decision = getKanbanTransitionDecision(scene, targetStatus);

    if (decision.type === "noop") {
      return;
    }

    if (decision.type === "blocked-planned") {
      setPendingTransition({ kind: "blocked-planned", scene, targetStatus });
      return;
    }

    if (decision.type === "confirm-advanced") {
      setPendingTransition({ kind: "confirm-advanced", scene, targetStatus });
      return;
    }

    void applyStatusChange(scene, targetStatus);
  }

  async function applyStatusChange(scene: KanbanSceneCardModel, targetStatus: SceneStatus) {
    const previousStatus = scene.status;
    setPendingSceneId(scene.id);
    setStatusOverrides((previous) => ({ ...previous, [scene.id]: targetStatus }));

    try {
      await mutation.mutateAsync({ sceneId: scene.id, status: targetStatus });
    } catch (error) {
      setStatusOverrides((previous) => ({ ...previous, [scene.id]: previousStatus }));
      setErrorMessage(readMutationError(error));
    }
  }

  function handleOpenPlanning() {
    if (!pendingTransition) {
      return;
    }

    const sceneId = pendingTransition.scene.id;
    setPendingTransition(null);
    onOpenSceneInEditor(sceneId);
  }

  function handleConfirmAdvancedTransition() {
    if (!pendingTransition || pendingTransition.kind !== "confirm-advanced") {
      return;
    }

    const { scene, targetStatus } = pendingTransition;
    setPendingTransition(null);
    void applyStatusChange(scene, targetStatus);
  }

  return (
    <section className="h-full overflow-y-auto bg-zinc-50 p-4 md:p-6">
      <div className="mx-auto grid max-w-7xl gap-4">
        <KanbanHeader />
        {errorMessage ? <FeedbackMessage variant="error">{errorMessage}</FeedbackMessage> : null}
        <DndContext
          sensors={sensors}
          onDragStart={handleDragStart}
          onDragEnd={handleDragEnd}
          onDragCancel={clearActiveDrag}
        >
          <div className="overflow-hidden rounded-lg border border-zinc-200 bg-white shadow-sm shadow-zinc-200/60">
            <div className="max-h-[calc(100vh-12rem)] overflow-auto">
              <div className="flex min-w-max gap-3 p-3">
                {model.columns.map((column) => (
                  <KanbanColumn
                    key={column.status}
                    column={column}
                    pendingSceneId={pendingSceneId}
                    onOpenSceneInEditor={onOpenSceneInEditor}
                    onRequestStatusChange={requestStatusChange}
                  />
                ))}
              </div>
            </div>
          </div>
          <DragOverlay>
            {activeScene ? (
              <KanbanSceneCard
                scene={activeScene}
                pending={false}
                overlay
                onOpenSceneInEditor={onOpenSceneInEditor}
                onRequestStatusChange={requestStatusChange}
              />
            ) : null}
          </DragOverlay>
        </DndContext>
      </div>
      {pendingTransition ? (
        <KanbanValidationModal
          transition={pendingTransition}
          onCancel={() => setPendingTransition(null)}
          onOpenPlanning={handleOpenPlanning}
          onConfirmAdvanced={handleConfirmAdvancedTransition}
        />
      ) : null}
    </section>
  );
}

function KanbanHeader() {
  return (
    <header>
      <h1 className="text-xl font-semibold text-zinc-950">Kanban</h1>
      <p className="mt-1 text-sm text-zinc-500">
        Gerencie o fluxo de producao das cenas sem alterar a ordem narrativa do manuscrito.
      </p>
    </header>
  );
}

function KanbanColumn({
  column,
  pendingSceneId,
  onOpenSceneInEditor,
  onRequestStatusChange,
}: {
  column: KanbanColumnModel;
  pendingSceneId: string | null;
  onOpenSceneInEditor: (sceneId: string) => void;
  onRequestStatusChange: (scene: KanbanSceneCardModel, targetStatus: SceneStatus) => void;
}) {
  const { setNodeRef, isOver } = useDroppable({ id: column.status });

  return (
    <section
      ref={setNodeRef}
      aria-label={column.label}
      className={`flex w-[260px] shrink-0 flex-col rounded-md border bg-zinc-50 transition-colors ${
        isOver ? "border-emerald-400 bg-emerald-50/70" : "border-zinc-200"
      }`}
    >
      <header className="sticky top-0 z-10 border-b border-zinc-200 bg-white px-3 py-3">
        <div className="flex items-start justify-between gap-2">
          <div>
            <h2 className="text-sm font-semibold text-zinc-950">{column.label}</h2>
            <p className="mt-1 text-xs text-zinc-500">
              {formatNumber(column.sceneCount)} cenas · {formatNumber(column.wordCount)} palavras
            </p>
          </div>
          <Badge variant="neutral">{column.status}</Badge>
        </div>
      </header>
      <div className="grid content-start gap-2 p-2">
        {column.scenes.length === 0 ? (
          <p className="rounded-md border border-dashed border-zinc-300 bg-white px-3 py-6 text-center text-xs text-zinc-500">
            Nenhuma cena
          </p>
        ) : (
          column.scenes.map((scene) => (
            <DraggableKanbanSceneCard
              key={scene.id}
              scene={scene}
              pending={pendingSceneId === scene.id}
              onOpenSceneInEditor={onOpenSceneInEditor}
              onRequestStatusChange={onRequestStatusChange}
            />
          ))
        )}
      </div>
    </section>
  );
}

function DraggableKanbanSceneCard({
  scene,
  pending,
  onOpenSceneInEditor,
  onRequestStatusChange,
}: {
  scene: KanbanSceneCardModel;
  pending: boolean;
  onOpenSceneInEditor: (sceneId: string) => void;
  onRequestStatusChange: (scene: KanbanSceneCardModel, targetStatus: SceneStatus) => void;
}) {
  const { attributes, listeners, setActivatorNodeRef, setNodeRef, transform, isDragging } = useDraggable({
    id: scene.id,
  });
  const style = {
    transform: CSS.Translate.toString(transform),
  };

  return (
    <KanbanSceneCard
      ref={setNodeRef}
      activatorRef={setActivatorNodeRef}
      dragAttributes={attributes}
      dragListeners={listeners}
      style={style}
      scene={scene}
      pending={pending}
      dragging={isDragging}
      onOpenSceneInEditor={onOpenSceneInEditor}
      onRequestStatusChange={onRequestStatusChange}
    />
  );
}

type KanbanSceneCardProps = {
  scene: KanbanSceneCardModel;
  pending: boolean;
  overlay?: boolean;
  dragging?: boolean;
  style?: CSSProperties;
  ref?: Ref<HTMLElement>;
  activatorRef?: (element: HTMLElement | null) => void;
  dragAttributes?: DraggableAttributes;
  dragListeners?: DraggableSyntheticListeners;
  onOpenSceneInEditor: (sceneId: string) => void;
  onRequestStatusChange: (scene: KanbanSceneCardModel, targetStatus: SceneStatus) => void;
};

function KanbanSceneCard({
  scene,
  pending,
  overlay = false,
  dragging = false,
  style,
  ref,
  activatorRef,
  dragAttributes,
  dragListeners,
  onOpenSceneInEditor,
  onRequestStatusChange,
}: KanbanSceneCardProps) {
  const gapCount = scene.scene.planningGaps.length;
  const povLabel = scene.scene.povCharacterName ?? "Sem POV";

  return (
    <article
      ref={ref}
      style={style}
      className={`grid gap-2 rounded-md border border-zinc-200 bg-white p-3 text-left shadow-sm shadow-zinc-200/50 transition ${
        dragging ? "opacity-40" : ""
      } ${overlay ? "w-[244px] shadow-lg" : ""}`}
    >
      <div className="flex items-start gap-2">
        <button
          ref={activatorRef}
          type="button"
          className="mt-0.5 inline-flex h-7 w-7 shrink-0 cursor-grab items-center justify-center rounded-md border border-zinc-200 bg-zinc-50 text-xs font-semibold text-zinc-500 transition hover:border-emerald-300 hover:bg-emerald-50 hover:text-emerald-800 focus:outline-none focus:ring-2 focus:ring-emerald-600 disabled:cursor-not-allowed disabled:opacity-50"
          aria-label={`Mover cena ${scene.scene.title}`}
          disabled={pending}
          {...dragAttributes}
          {...dragListeners}
        >
          ::
        </button>
        <button
          type="button"
          aria-label={`Abrir cena ${scene.scene.title}`}
          onClick={() => onOpenSceneInEditor(scene.id)}
          className="min-w-0 flex-1 text-left focus:outline-none focus:ring-2 focus:ring-emerald-600 focus:ring-offset-2"
        >
          <h3 className="line-clamp-2 text-sm font-semibold text-zinc-950">{scene.scene.title}</h3>
          <p className="mt-1 line-clamp-1 text-xs text-zinc-500">{scene.chapterTitle}</p>
          <p className="mt-0.5 line-clamp-1 text-[11px] uppercase text-zinc-400">{scene.sectionTitle}</p>
        </button>
      </div>

      <div className="flex flex-wrap gap-1.5">
        <Badge variant="neutral">{formatNumber(scene.scene.wordCount)} palavras</Badge>
        <Badge variant={scene.scene.povCharacterName ? "outline" : "neutral"}>{povLabel}</Badge>
        {gapCount > 0 ? (
          <Badge className="bg-amber-100 text-amber-900">
            {formatNumber(gapCount)} {gapCount === 1 ? "lacuna" : "lacunas"}
          </Badge>
        ) : null}
      </div>

      <label className="grid gap-1 text-xs font-medium text-zinc-600">
        Status
        <select
          className="min-h-8 rounded-md border border-zinc-300 bg-white px-2 py-1 text-sm text-zinc-800 focus:border-emerald-500 focus:outline-none focus:ring-2 focus:ring-emerald-100 disabled:cursor-not-allowed disabled:opacity-60"
          value={scene.status}
          disabled={pending}
          aria-label={`Status de ${scene.scene.title}`}
          onChange={(event) => onRequestStatusChange(scene, event.target.value as SceneStatus)}
        >
          {KANBAN_STATUS_COLUMNS.map((column) => (
            <option key={column.status} value={column.status}>
              {column.label}
            </option>
          ))}
        </select>
      </label>
    </article>
  );
}

function KanbanValidationModal({
  transition,
  onCancel,
  onOpenPlanning,
  onConfirmAdvanced,
}: {
  transition: PendingTransition;
  onCancel: () => void;
  onOpenPlanning: () => void;
  onConfirmAdvanced: () => void;
}) {
  const isBlocked = transition.kind === "blocked-planned";
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-zinc-950/40 px-4" role="presentation">
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="kanban-validation-title"
        className="w-full max-w-md rounded-lg border border-zinc-200 bg-white p-5 shadow-xl"
      >
        <h2 id="kanban-validation-title" className="text-lg font-semibold text-zinc-950">
          {isBlocked ? "Planejamento incompleto" : "Avancar com lacunas?"}
        </h2>
        <p className="mt-2 text-sm text-zinc-600">
          {isBlocked
            ? "Para mover esta cena para Planejada, complete os campos narrativos abaixo."
            : "Esta cena ainda tem lacunas de planejamento. Voce pode avancar o status, mas a lacuna continuara visivel."}
        </p>
        <ul className="mt-4 grid gap-2">
          {transition.scene.scene.planningGaps.map((gap) => (
            <li key={gap} className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-900">
              {gap}
            </li>
          ))}
        </ul>
        <div className="mt-5 flex flex-wrap justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onCancel}>
            Cancelar
          </Button>
          {isBlocked ? (
            <Button type="button" onClick={onOpenPlanning}>
              Abrir planejamento
            </Button>
          ) : (
            <Button type="button" onClick={onConfirmAdvanced}>
              Mover mesmo assim
            </Button>
          )}
        </div>
      </section>
    </div>
  );
}

function isSceneStatus(value: string): value is SceneStatus {
  return KANBAN_STATUS_COLUMNS.some((column) => column.status === value);
}

function readMutationError(error: unknown) {
  if (error instanceof ApiError && error.message) {
    return error.message;
  }

  return "Nao foi possivel mover a cena agora. A coluna foi restaurada.";
}

function formatNumber(value: number) {
  return new Intl.NumberFormat("pt-BR").format(value);
}
