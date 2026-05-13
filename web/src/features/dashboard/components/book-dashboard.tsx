"use client";

import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { useBookDashboard } from "@/features/dashboard/api/dashboard-hooks";
import type {
  BookDashboardResponse,
  DashboardSceneSummaryResponse,
  EntityUsageResponse,
  PovStatsResponse,
  StatusCountResponse,
} from "@/features/dashboard/types";
import type { SceneStatus } from "@/features/scenes/types";

type BookDashboardProps = {
  bookId: string;
};

const statusLabels: Record<SceneStatus, string> = {
  IDEA: "Ideia",
  PLANNED: "Planejada",
  DRAFT: "Rascunho",
  WRITTEN: "Escrita",
  REVISED: "Revisada",
  FINAL: "Final",
};

export function BookDashboard({ bookId }: BookDashboardProps) {
  const dashboardQuery = useBookDashboard(bookId);

  return (
    <section className="h-full overflow-y-auto bg-zinc-50 p-4 md:p-6">
      <div className="mx-auto grid max-w-6xl gap-4">
        <header>
          <h1 className="text-xl font-semibold text-zinc-950">Visão geral</h1>
          <p className="mt-1 text-sm text-zinc-500">
            Acompanhe o progresso e encontre lacunas narrativas do seu livro.
          </p>
        </header>

        {dashboardQuery.isLoading ? <LoadingState label="Carregando visão geral..." /> : null}

        {dashboardQuery.isError ? (
          <ErrorState message="Não foi possível carregar a visão geral. Verifique o backend e tente novamente." />
        ) : null}

        {dashboardQuery.data ? <DashboardContent dashboard={dashboardQuery.data} /> : null}
      </div>
    </section>
  );
}

function DashboardContent({ dashboard }: { dashboard: BookDashboardResponse }) {
  const planningPercent = clampPercent(dashboard.planningProgress.plannedScenesPercent);
  const [selectedStatus, setSelectedStatus] = useState<SceneStatus | null>(null);
  const selectedStatusDetails = selectedStatus
    ? dashboard.scenesByStatus.find((status) => status.status === selectedStatus) ?? null
    : null;

  return (
    <div className="grid gap-4">
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <MetricCard label="Total de palavras" value={formatNumber(dashboard.totalWordCount)} />
        <MetricCard label="Total de cenas" value={formatNumber(dashboard.totalScenes)} />
        <MetricCard
          label="Planejamento completo"
          value={`${formatNumber(dashboard.planningProgress.plannedScenesCount)} / ${formatNumber(dashboard.totalScenes)}`}
          helper="POV, objetivo, conflito e resultado"
        />
        <MetricCard
          label="Capítulos e seções"
          value={`${formatNumber(dashboard.totalChapters)} capítulos`}
          helper={`${formatNumber(dashboard.totalSections)} seções`}
        />
      </div>

      {dashboard.totalScenes === 0 ? (
        <EmptyState
          title="Este livro ainda não tem cenas."
          description="Crie cenas no modo Cenas para acompanhar progresso, POVs e lacunas narrativas aqui."
        />
      ) : null}

      <Card className="p-4">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <h2 className="text-base font-semibold text-zinc-950">Planejamento narrativo</h2>
            <p className="mt-1 text-sm text-zinc-500">
              {formatNumber(dashboard.planningProgress.plannedScenesCount)} de {formatNumber(dashboard.totalScenes)} cenas
              com planejamento completo. Cena completa quando possui POV, objetivo, conflito e resultado.
            </p>
          </div>
          <span className="text-2xl font-semibold text-zinc-950">{formatPercent(planningPercent)}</span>
        </div>

        <div className="mt-4 h-3 overflow-hidden rounded-full bg-zinc-100">
          <div className="h-full rounded-full bg-emerald-500" style={{ width: `${planningPercent}%` }} />
        </div>
      </Card>

      <section className="grid gap-4 xl:grid-cols-[minmax(0,1.1fr)_minmax(0,0.9fr)]">
        <Card className="p-4">
          <SectionHeader title="Cenas por status" description="Distribuição do trabalho por etapa de escrita." />
          <div className="mt-4 grid gap-2 sm:grid-cols-2">
            {dashboard.scenesByStatus.map((status) => (
              <StatusCard
                key={status.status}
                status={status}
                onOpen={() => setSelectedStatus(status.status)}
              />
            ))}
          </div>
        </Card>

        <Card className="p-4">
          <SectionHeader title="Lacunas narrativas" description="Pontos que talvez mereçam revisão." />
          <div className="mt-4 grid gap-2 sm:grid-cols-2 xl:grid-cols-1">
            <GapCard label="Sem POV" value={dashboard.narrativeGaps.scenesWithoutPov} />
            <GapCard label="Sem objetivo" value={dashboard.narrativeGaps.scenesWithoutGoal} />
            <GapCard label="Sem conflito" value={dashboard.narrativeGaps.scenesWithoutConflict} />
            <GapCard label="Sem resultado" value={dashboard.narrativeGaps.scenesWithoutOutcome} />
            <GapCard label="Sem localização" value={dashboard.narrativeGaps.scenesWithoutMainLocation} />
            <GapCard label="Sem participantes" value={dashboard.narrativeGaps.scenesWithoutParticipants} />
          </div>
        </Card>
      </section>

      <section className="grid gap-4 xl:grid-cols-2">
        <Card className="p-4">
          <SectionHeader title="POV" description="Cenas e palavras por ponto de vista." />
          <div className="mt-4">
            <PovList items={dashboard.povStats} />
          </div>
        </Card>

        <div className="grid gap-4">
          <UsageCard title="Personagens mais usados" items={dashboard.mostUsedCharacters} emptyMessage="Nenhum participante vinculado." />
          <UsageCard title="Localizações mais usadas" items={dashboard.mostUsedLocations} emptyMessage="Nenhuma localização vinculada." />
          <UsageCard title="Itens mais usados" items={dashboard.mostUsedItems} emptyMessage="Nenhum item vinculado." />
        </div>
      </section>

      {selectedStatusDetails ? (
        <StatusScenesModal status={selectedStatusDetails} onClose={() => setSelectedStatus(null)} />
      ) : null}
    </div>
  );
}

function StatusCard({ status, onOpen }: { status: StatusCountResponse; onOpen: () => void }) {
  return (
    <div className="flex min-h-[112px] flex-col justify-between rounded-md border border-zinc-200 bg-zinc-50 p-3">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h3 className="text-sm font-medium text-zinc-800">{statusLabels[status.status]}</h3>
          <p className="mt-2 text-xs text-zinc-500">{formatNumber(status.wordCount)} palavras</p>
        </div>
        <Badge variant="outline">{formatNumber(status.scenesCount)} cenas</Badge>
      </div>

      <button type="button" onClick={onOpen} className="mt-4 w-fit text-xs font-medium text-zinc-700 hover:text-zinc-950">
        Ver cenas
      </button>
    </div>
  );
}

function StatusScenesModal({ status, onClose }: { status: StatusCountResponse; onClose: () => void }) {
  const [expandedSceneId, setExpandedSceneId] = useState<string | null>(null);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-zinc-950/35 p-4" role="dialog" aria-modal="true">
      <div className="grid max-h-[88vh] w-full max-w-4xl grid-rows-[auto_1fr] overflow-hidden rounded-lg border border-zinc-200 bg-white shadow-xl">
        <header className="flex items-start justify-between gap-4 border-b border-zinc-200 p-4">
          <div>
            <h2 className="text-lg font-semibold text-zinc-950">Cenas em {statusLabels[status.status]}</h2>
            <p className="mt-1 text-sm text-zinc-500">
              {formatNumber(status.scenesCount)} cenas · {formatNumber(status.wordCount)} palavras
            </p>
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
          <SceneSummaryList
            scenes={status.scenes}
            expandedSceneId={expandedSceneId}
            onToggleScene={(sceneId) => setExpandedSceneId((current) => (current === sceneId ? null : sceneId))}
          />
        </div>
      </div>
    </div>
  );
}

function SceneSummaryList({
  scenes,
  expandedSceneId,
  onToggleScene,
}: {
  scenes: DashboardSceneSummaryResponse[];
  expandedSceneId: string | null;
  onToggleScene: (sceneId: string) => void;
}) {
  if (scenes.length === 0) {
    return <EmptyState title="Nenhuma cena neste status." size="sm" />;
  }

  return (
    <ol className="grid gap-2">
      {scenes.map((scene) => (
        <li key={scene.sceneId} className="rounded-md border border-zinc-200 bg-white p-3">
          <button type="button" onClick={() => onToggleScene(scene.sceneId)} className="w-full text-left">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="truncate text-sm font-medium text-zinc-900">{scene.title}</p>
                <p className="mt-1 text-xs text-zinc-500">{formatSceneLocation(scene)}</p>
              </div>
              <Badge variant="outline">{statusLabels[scene.status]}</Badge>
            </div>
            <p className="mt-2 text-xs text-zinc-500">{formatNumber(scene.wordCount)} palavras</p>
            <p className="mt-2 text-xs font-medium text-zinc-500">
              {expandedSceneId === scene.sceneId ? "Ocultar detalhes" : "Ver detalhes"}
            </p>
          </button>

          {expandedSceneId === scene.sceneId ? <SceneDetails scene={scene} /> : null}
        </li>
      ))}
    </ol>
  );
}

function SceneDetails({ scene }: { scene: DashboardSceneSummaryResponse }) {
  const gaps = getSceneGaps(scene);

  return (
    <div className="mt-3 grid gap-3 border-t border-zinc-200 pt-3">
      {hasText(scene.summary) ? <DetailBlock label="Resumo" value={scene.summary} /> : null}

      <div className="grid gap-2 sm:grid-cols-2">
        <DetailLine label="POV" value={scene.povCharacterName} fallback="Sem POV" />
        <DetailLine label="Localização principal" value={scene.mainLocationName} fallback="Sem localização" />
        <DetailLine label="Participantes" value={formatNames(scene.participantNames)} fallback="Sem participantes" />
        <DetailLine label="Itens" value={formatNames(scene.itemNames)} fallback="Sem itens" />
      </div>

      <div className="grid gap-2">
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

function MetricCard({ label, value, helper }: { label: string; value: string; helper?: string }) {
  return (
    <Card className="p-4">
      <p className="text-xs font-medium uppercase text-zinc-500">{label}</p>
      <p className="mt-2 text-2xl font-semibold text-zinc-950">{value}</p>
      {helper ? <p className="mt-1 text-sm text-zinc-500">{helper}</p> : null}
    </Card>
  );
}

function SectionHeader({ title, description }: { title: string; description: string }) {
  return (
    <div>
      <h2 className="text-base font-semibold text-zinc-950">{title}</h2>
      <p className="mt-1 text-sm text-zinc-500">{description}</p>
    </div>
  );
}

function GapCard({ label, value }: { label: string; value: number }) {
  const hasGap = value > 0;

  return (
    <div
      className={`flex items-center justify-between gap-3 rounded-md border p-3 ${
        hasGap ? "border-amber-200 bg-amber-50 text-amber-950" : "border-emerald-200 bg-emerald-50 text-emerald-950"
      }`}
    >
      <span className="text-sm font-medium">{label}</span>
      <span className="text-sm font-semibold">{formatNumber(value)}</span>
    </div>
  );
}

function PovList({ items }: { items: PovStatsResponse[] }) {
  if (items.length === 0) {
    return <EmptyState title="Nenhum POV vinculado." size="sm" />;
  }

  return (
    <ol className="grid gap-2">
      {items.map((item, index) => (
        <li key={item.characterId} className="flex items-center justify-between gap-3 rounded-md border border-zinc-200 bg-zinc-50 p-3">
          <div className="min-w-0">
            <p className="truncate text-sm font-medium text-zinc-900">
              {index + 1}. {item.name}
            </p>
            <p className="mt-1 text-xs text-zinc-500">{formatNumber(item.wordCount)} palavras</p>
          </div>
          <Badge variant="outline">{formatNumber(item.scenesCount)} cenas</Badge>
        </li>
      ))}
    </ol>
  );
}

function UsageCard({ title, items, emptyMessage }: { title: string; items: EntityUsageResponse[]; emptyMessage: string }) {
  return (
    <Card className="p-4">
      <SectionHeader title={title} description="Quantidade de cenas vinculadas." />
      <div className="mt-4">
        {items.length === 0 ? (
          <EmptyState title={emptyMessage} size="sm" />
        ) : (
          <ol className="grid gap-2">
            {items.map((item, index) => (
              <li
                key={item.id}
                className="flex items-center justify-between gap-3 rounded-md border border-zinc-200 bg-zinc-50 p-3"
              >
                <span className="min-w-0 truncate text-sm font-medium text-zinc-900">
                  {index + 1}. {item.name}
                </span>
                <Badge variant="outline">{formatNumber(item.scenesCount)} cenas</Badge>
              </li>
            ))}
          </ol>
        )}
      </div>
    </Card>
  );
}

function formatNumber(value: number) {
  return new Intl.NumberFormat("pt-BR").format(value);
}

function formatPercent(value: number) {
  return `${formatNumber(Math.round(value))}%`;
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

function clampPercent(value: number) {
  if (Number.isNaN(value)) {
    return 0;
  }

  return Math.max(0, Math.min(100, value));
}
