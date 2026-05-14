"use client";

import { Badge } from "@/components/ui/badge";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { useCharacter } from "@/features/characters/api/characters-hooks";
import { useItem } from "@/features/items/api/items-hooks";
import { useLocation } from "@/features/locations/api/locations-hooks";
import type { BookDashboardResponse, DashboardSceneSummaryResponse } from "@/features/dashboard/types";
import type { SceneStatus } from "@/features/scenes/types";

export type DashboardGapKind =
  | "withoutPov"
  | "withoutGoal"
  | "withoutConflict"
  | "withoutOutcome"
  | "withoutMainLocation"
  | "withoutParticipants";

export type DashboardDetailTarget =
  | { type: "scene"; sceneId: string }
  | { type: "status"; status: SceneStatus }
  | { type: "gap"; gap: DashboardGapKind }
  | { type: "character"; id: string }
  | { type: "location"; id: string }
  | { type: "item"; id: string };

type DashboardDetailModalProps = {
  dashboard: BookDashboardResponse;
  target: DashboardDetailTarget;
  onClose: () => void;
  onTargetChange: (target: DashboardDetailTarget) => void;
};

const statusLabels: Record<SceneStatus, string> = {
  IDEA: "Ideia",
  PLANNED: "Planejada",
  DRAFT: "Rascunho",
  WRITTEN: "Escrita",
  REVISED: "Revisada",
  FINAL: "Final",
};

const gapLabels: Record<DashboardGapKind, { title: string; description: string }> = {
  withoutPov: {
    title: "Cenas sem POV",
    description: "Cenas sem personagem de ponto de vista definido.",
  },
  withoutGoal: {
    title: "Cenas sem objetivo",
    description: "Cenas sem objetivo narrativo cadastrado.",
  },
  withoutConflict: {
    title: "Cenas sem conflito",
    description: "Cenas sem conflito narrativo cadastrado.",
  },
  withoutOutcome: {
    title: "Cenas sem resultado",
    description: "Cenas sem resultado ou virada final cadastrada.",
  },
  withoutMainLocation: {
    title: "Cenas sem localização",
    description: "Cenas sem localização principal vinculada.",
  },
  withoutParticipants: {
    title: "Cenas sem participantes",
    description: "Cenas sem personagens participantes vinculados.",
  },
};

export function DashboardDetailModal({ dashboard, target, onClose, onTargetChange }: DashboardDetailModalProps) {
  const scenes = getAllScenes(dashboard);
  const characterQuery = useCharacter(target.type === "character" ? target.id : null);
  const locationQuery = useLocation(target.type === "location" ? target.id : null);
  const itemQuery = useItem(target.type === "item" ? target.id : null);
  const title = getModalTitle(target, dashboard, scenes);
  const subtitle = getModalSubtitle(target, dashboard, scenes);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-zinc-950/35 p-4" role="dialog" aria-modal="true">
      <div className="grid max-h-[88vh] w-full max-w-4xl grid-rows-[auto_1fr] overflow-hidden rounded-lg border border-zinc-200 bg-white shadow-xl">
        <header className="flex items-start justify-between gap-4 border-b border-zinc-200 p-4">
          <div>
            <h2 className="text-lg font-semibold text-zinc-950">{title}</h2>
            {subtitle ? <p className="mt-1 text-sm text-zinc-500">{subtitle}</p> : null}
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-zinc-200 px-3 py-2 text-sm font-medium text-zinc-700 hover:bg-zinc-50"
          >
            Fechar
          </button>
        </header>

        <div className="overflow-y-auto p-4">
          {target.type === "scene" ? (
            <SceneDetailView scene={scenes.find((scene) => scene.sceneId === target.sceneId) ?? null} />
          ) : target.type === "status" ? (
            <SceneListView scenes={getScenesForStatus(dashboard, target.status)} onSelectScene={(sceneId) => onTargetChange({ type: "scene", sceneId })} />
          ) : target.type === "gap" ? (
            <GapDetailView gap={target.gap} scenes={getScenesForGap(scenes, target.gap)} onSelectScene={(sceneId) => onTargetChange({ type: "scene", sceneId })} />
          ) : target.type === "character" ? (
            <CharacterDetailView dashboard={dashboard} characterId={target.id} query={characterQuery} />
          ) : target.type === "location" ? (
            <LocationDetailView dashboard={dashboard} locationId={target.id} query={locationQuery} />
          ) : (
            <ItemDetailView dashboard={dashboard} itemId={target.id} query={itemQuery} />
          )}
        </div>
      </div>
    </div>
  );
}

function SceneListView({
  scenes,
  onSelectScene,
}: {
  scenes: DashboardSceneSummaryResponse[];
  onSelectScene: (sceneId: string) => void;
}) {
  if (scenes.length === 0) {
    return <EmptyState title="Nenhuma cena encontrada." size="sm" />;
  }

  return (
    <ol className="grid gap-2">
      {scenes.map((scene) => (
        <li key={scene.sceneId}>
          <button
            type="button"
            onClick={() => onSelectScene(scene.sceneId)}
            className="w-full rounded-md border border-zinc-200 bg-white p-3 text-left transition-[transform,background-color,box-shadow] duration-150 ease-out hover:-translate-y-0.5 hover:bg-zinc-50 hover:shadow-sm hover:shadow-zinc-200/70"
          >
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="truncate text-sm font-medium text-zinc-900">{scene.title}</p>
                <p className="mt-1 text-xs text-zinc-500">{formatSceneLocation(scene)}</p>
              </div>
              <Badge variant="outline">{statusLabels[scene.status]}</Badge>
            </div>
            <p className="mt-2 text-xs text-zinc-500">{formatNumber(scene.wordCount)} palavras</p>
          </button>
        </li>
      ))}
    </ol>
  );
}

function GapDetailView({
  gap,
  scenes,
  onSelectScene,
}: {
  gap: DashboardGapKind;
  scenes: DashboardSceneSummaryResponse[];
  onSelectScene: (sceneId: string) => void;
}) {
  return (
    <div className="grid gap-4">
      <div className="rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-950">
        <p className="font-medium">{gapLabels[gap].description}</p>
        <p className="mt-1">{formatNumber(scenes.length)} cenas afetadas.</p>
      </div>
      <SceneListView scenes={scenes} onSelectScene={onSelectScene} />
    </div>
  );
}

function SceneDetailView({ scene }: { scene: DashboardSceneSummaryResponse | null }) {
  if (!scene) {
    return <EmptyState title="Cena não encontrada no dashboard." size="sm" />;
  }

  const gaps = getSceneGaps(scene);

  return (
    <div className="grid gap-4">
      <div className="rounded-md border border-zinc-200 bg-zinc-50 p-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h3 className="text-lg font-semibold text-zinc-950">{scene.title}</h3>
            <p className="mt-1 text-sm text-zinc-500">{formatSceneLocation(scene)}</p>
          </div>
          <Badge variant="outline">{statusLabels[scene.status]}</Badge>
        </div>
        <p className="mt-3 text-sm text-zinc-500">{formatNumber(scene.wordCount)} palavras</p>
      </div>

      {hasText(scene.summary) ? <DetailBlock label="Resumo" value={scene.summary} /> : null}

      <div className="grid gap-3 sm:grid-cols-2">
        <DetailLine label="POV" value={scene.povCharacterName} fallback="Sem POV" />
        <DetailLine label="Localização principal" value={scene.mainLocationName} fallback="Sem localização" />
        <DetailLine label="Participantes" value={formatNames(scene.participantNames)} fallback="Sem participantes" />
        <DetailLine label="Itens" value={formatNames(scene.itemNames)} fallback="Sem itens" />
      </div>

      <div className="grid gap-3">
        <DetailBlock label="Objetivo" value={scene.goal} fallback="Sem objetivo" />
        <DetailBlock label="Conflito" value={scene.conflict} fallback="Sem conflito" />
        <DetailBlock label="Resultado" value={scene.outcome} fallback="Sem resultado" />
        <DetailBlock label="Notas da cena" value={scene.planningNotes} fallback="Sem notas" />
      </div>

      <div>
        <p className="text-xs font-medium uppercase text-zinc-500">Lacunas</p>
        <div className="mt-2 flex flex-wrap gap-2">
          {gaps.length === 0 ? (
            <Badge className="bg-emerald-100 text-emerald-800">Sem lacunas principais</Badge>
          ) : (
            gaps.map((gap) => (
              <Badge key={gap} className="bg-amber-100 text-amber-900">
                {gap}
              </Badge>
            ))
          )}
        </div>
      </div>
    </div>
  );
}

function CharacterDetailView({
  dashboard,
  characterId,
  query,
}: {
  dashboard: BookDashboardResponse;
  characterId: string;
  query: ReturnType<typeof useCharacter>;
}) {
  if (query.isLoading) {
    return <LoadingState label="Carregando personagem..." />;
  }
  if (query.isError) {
    return <ErrorState message="Não foi possível carregar o personagem." />;
  }
  if (!query.data) {
    return <EmptyState title="Personagem não encontrado." size="sm" />;
  }

  const usage = dashboard.mostUsedCharacters.find((item) => item.id === characterId);
  const povStats = dashboard.povStats.find((item) => item.characterId === characterId);

  return (
    <div className="grid gap-4">
      <EntitySummary title={query.data.name} subtitle={query.data.nickname ? `Apelido: ${query.data.nickname}` : null} />
      <div className="grid gap-3 sm:grid-cols-3">
        <MetricBox label="Cenas vinculadas" value={formatNumber(usage?.scenesCount ?? 0)} />
        <MetricBox label="Cenas como POV" value={formatNumber(povStats?.scenesCount ?? 0)} />
        <MetricBox label="Palavras como POV" value={formatNumber(povStats?.wordCount ?? 0)} />
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <DetailLine label="Idade" value={query.data.age == null ? null : String(query.data.age)} fallback="Não informada" />
        <DetailLine label="Sexo" value={query.data.sex} fallback="Não informado" />
        <DetailLine label="Função narrativa" value={query.data.narrativeFunction} fallback="Não informada" />
      </div>
      <DetailBlock label="Objetivo" value={query.data.goal} fallback="Sem objetivo" />
      <DetailBlock label="Conflito" value={query.data.conflict} fallback="Sem conflito" />
      <DetailBlock label="Arco" value={query.data.arc} fallback="Sem arco" />
      <DetailBlock label="Descrição física" value={query.data.physicalDescription} fallback="Sem descrição física" />
      <DetailBlock label="Personalidade" value={query.data.personality} fallback="Sem personalidade" />
      <DetailBlock label="Biografia" value={query.data.biography} fallback="Sem biografia" />
      <DetailBlock label="Notas" value={query.data.notes} fallback="Sem notas" />
    </div>
  );
}

function LocationDetailView({
  dashboard,
  locationId,
  query,
}: {
  dashboard: BookDashboardResponse;
  locationId: string;
  query: ReturnType<typeof useLocation>;
}) {
  if (query.isLoading) {
    return <LoadingState label="Carregando localização..." />;
  }
  if (query.isError) {
    return <ErrorState message="Não foi possível carregar a localização." />;
  }
  if (!query.data) {
    return <EmptyState title="Localização não encontrada." size="sm" />;
  }

  const usage = dashboard.mostUsedLocations.find((item) => item.id === locationId);

  return (
    <div className="grid gap-4">
      <EntitySummary title={query.data.name} subtitle={query.data.type} />
      <MetricBox label="Cenas como localização principal" value={formatNumber(usage?.scenesCount ?? 0)} />
      <DetailBlock label="Descrição" value={query.data.description} fallback="Sem descrição" />
      <DetailBlock label="Contexto histórico" value={query.data.historyContext} fallback="Sem contexto histórico" />
      <DetailBlock label="Importância narrativa" value={query.data.narrativeImportance} fallback="Sem importância narrativa" />
      <DetailBlock label="Notas" value={query.data.notes} fallback="Sem notas" />
    </div>
  );
}

function ItemDetailView({
  dashboard,
  itemId,
  query,
}: {
  dashboard: BookDashboardResponse;
  itemId: string;
  query: ReturnType<typeof useItem>;
}) {
  if (query.isLoading) {
    return <LoadingState label="Carregando item..." />;
  }
  if (query.isError) {
    return <ErrorState message="Não foi possível carregar o item." />;
  }
  if (!query.data) {
    return <EmptyState title="Item não encontrado." size="sm" />;
  }

  const usage = dashboard.mostUsedItems.find((item) => item.id === itemId);

  return (
    <div className="grid gap-4">
      <EntitySummary title={query.data.name} subtitle={query.data.type} />
      <div className="grid gap-3 sm:grid-cols-2">
        <MetricBox label="Cenas vinculadas" value={formatNumber(usage?.scenesCount ?? 0)} />
        <DetailLine label="Dono atual" value={query.data.currentOwnerCharacter?.name ?? null} fallback="Sem dono" />
      </div>
      <DetailBlock label="Descrição" value={query.data.description} fallback="Sem descrição" />
      <DetailBlock label="Origem" value={query.data.origin} fallback="Sem origem" />
      <DetailBlock label="Importância narrativa" value={query.data.narrativeImportance} fallback="Sem importância narrativa" />
      <DetailBlock label="Notas" value={query.data.notes} fallback="Sem notas" />
    </div>
  );
}

function EntitySummary({ title, subtitle }: { title: string; subtitle?: string | null }) {
  return (
    <div className="rounded-md border border-zinc-200 bg-zinc-50 p-4">
      <h3 className="text-lg font-semibold text-zinc-950">{title}</h3>
      {hasText(subtitle) ? <p className="mt-1 text-sm text-zinc-500">{subtitle}</p> : null}
    </div>
  );
}

function MetricBox({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md bg-zinc-50 p-3">
      <p className="text-xs font-medium uppercase text-zinc-500">{label}</p>
      <p className="mt-1 text-lg font-semibold text-zinc-950">{value}</p>
    </div>
  );
}

function DetailLine({ label, value, fallback }: { label: string; value: string | null; fallback: string }) {
  return (
    <div className="rounded-md bg-zinc-50 p-3">
      <p className="text-xs font-medium uppercase text-zinc-500">{label}</p>
      <p className={`mt-1 text-sm ${hasText(value) ? "text-zinc-900" : "text-zinc-500"}`}>{hasText(value) ? value : fallback}</p>
    </div>
  );
}

function DetailBlock({ label, value, fallback }: { label: string; value: string | null; fallback?: string }) {
  if (!hasText(value) && !fallback) {
    return null;
  }

  return (
    <div className="rounded-md bg-zinc-50 p-3">
      <p className="text-xs font-medium uppercase text-zinc-500">{label}</p>
      <p className={`mt-1 whitespace-pre-wrap text-sm leading-6 ${hasText(value) ? "text-zinc-900" : "text-zinc-500"}`}>
        {hasText(value) ? value : fallback}
      </p>
    </div>
  );
}

function getModalTitle(target: DashboardDetailTarget, dashboard: BookDashboardResponse, scenes: DashboardSceneSummaryResponse[]) {
  if (target.type === "scene") {
    return scenes.find((scene) => scene.sceneId === target.sceneId)?.title ?? "Detalhe da cena";
  }
  if (target.type === "status") {
    return `Cenas em ${statusLabels[target.status]}`;
  }
  if (target.type === "gap") {
    return gapLabels[target.gap].title;
  }
  if (target.type === "character") {
    return dashboard.mostUsedCharacters.find((item) => item.id === target.id)?.name ?? "Detalhe do personagem";
  }
  if (target.type === "location") {
    return dashboard.mostUsedLocations.find((item) => item.id === target.id)?.name ?? "Detalhe da localização";
  }
  return dashboard.mostUsedItems.find((item) => item.id === target.id)?.name ?? "Detalhe do item";
}

function getModalSubtitle(target: DashboardDetailTarget, dashboard: BookDashboardResponse, scenes: DashboardSceneSummaryResponse[]) {
  if (target.type === "status") {
    const status = dashboard.scenesByStatus.find((item) => item.status === target.status);
    return status ? `${formatNumber(status.scenesCount)} cenas - ${formatNumber(status.wordCount)} palavras` : null;
  }
  if (target.type === "gap") {
    return `${formatNumber(getScenesForGap(scenes, target.gap).length)} cenas afetadas`;
  }
  return null;
}

function getAllScenes(dashboard: BookDashboardResponse) {
  return dashboard.scenesByStatus.flatMap((status) => status.scenes);
}

function getScenesForStatus(dashboard: BookDashboardResponse, status: SceneStatus) {
  return dashboard.scenesByStatus.find((item) => item.status === status)?.scenes ?? [];
}

function getScenesForGap(scenes: DashboardSceneSummaryResponse[], gap: DashboardGapKind) {
  return scenes.filter((scene) => {
    if (gap === "withoutPov") {
      return !hasText(scene.povCharacterName);
    }
    if (gap === "withoutGoal") {
      return !hasText(scene.goal);
    }
    if (gap === "withoutConflict") {
      return !hasText(scene.conflict);
    }
    if (gap === "withoutOutcome") {
      return !hasText(scene.outcome);
    }
    if (gap === "withoutMainLocation") {
      return !hasText(scene.mainLocationName);
    }
    return scene.participantNames.length === 0;
  });
}

function getSceneGaps(scene: DashboardSceneSummaryResponse) {
  const gaps: string[] = [];

  if (!hasText(scene.povCharacterName)) {
    gaps.push("Sem POV");
  }
  if (!hasText(scene.goal)) {
    gaps.push("Sem objetivo");
  }
  if (!hasText(scene.conflict)) {
    gaps.push("Sem conflito");
  }
  if (!hasText(scene.outcome)) {
    gaps.push("Sem resultado");
  }
  if (!hasText(scene.mainLocationName)) {
    gaps.push("Sem localização");
  }
  if (scene.participantNames.length === 0) {
    gaps.push("Sem participantes");
  }

  return gaps;
}

function formatSceneLocation(scene: DashboardSceneSummaryResponse) {
  if (scene.sectionTitle) {
    return `${scene.chapterTitle} - ${scene.sectionTitle}`;
  }

  return scene.chapterTitle;
}

function formatNames(names: string[]) {
  return names.length > 0 ? names.join(", ") : null;
}

function hasText(value: string | null | undefined) {
  return typeof value === "string" && value.trim().length > 0;
}

function formatNumber(value: number) {
  return new Intl.NumberFormat("pt-BR").format(value);
}
