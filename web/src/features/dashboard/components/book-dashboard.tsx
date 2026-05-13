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
  const [expandedStatus, setExpandedStatus] = useState<SceneStatus | null>(null);

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
                isExpanded={expandedStatus === status.status}
                onToggle={() => setExpandedStatus((current) => (current === status.status ? null : status.status))}
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
    </div>
  );
}

function StatusCard({
  status,
  isExpanded,
  onToggle,
}: {
  status: StatusCountResponse;
  isExpanded: boolean;
  onToggle: () => void;
}) {
  return (
    <div className="rounded-md border border-zinc-200 bg-zinc-50 p-3">
      <button
        type="button"
        aria-expanded={isExpanded}
        onClick={onToggle}
        className="flex w-full items-start justify-between gap-3 text-left"
      >
        <span>
          <span className="block text-sm font-medium text-zinc-800">{statusLabels[status.status]}</span>
          <span className="mt-2 block text-xs text-zinc-500">{formatNumber(status.wordCount)} palavras</span>
        </span>
        <span className="flex shrink-0 flex-col items-end gap-2">
          <Badge variant="outline">{formatNumber(status.scenesCount)} cenas</Badge>
          <span className="text-xs font-medium text-zinc-500">{isExpanded ? "Ocultar" : "Ver cenas"}</span>
        </span>
      </button>

      {isExpanded ? (
        <div className="mt-3 border-t border-zinc-200 pt-3">
          <SceneSummaryList scenes={status.scenes} />
        </div>
      ) : null}
    </div>
  );
}

function SceneSummaryList({ scenes }: { scenes: DashboardSceneSummaryResponse[] }) {
  if (scenes.length === 0) {
    return <p className="text-sm text-zinc-500">Nenhuma cena neste status.</p>;
  }

  return (
    <ol className="grid gap-2">
      {scenes.map((scene) => (
        <li key={scene.sceneId} className="rounded-md border border-zinc-200 bg-white p-3">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <p className="truncate text-sm font-medium text-zinc-900">{scene.title}</p>
              <p className="mt-1 text-xs text-zinc-500">{formatSceneLocation(scene)}</p>
            </div>
            <Badge variant="outline">{statusLabels[scene.status]}</Badge>
          </div>
          <p className="mt-2 text-xs text-zinc-500">{formatNumber(scene.wordCount)} palavras</p>
        </li>
      ))}
    </ol>
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
    return `${scene.chapterTitle} · ${scene.sectionTitle}`;
  }

  return scene.chapterTitle;
}

function clampPercent(value: number) {
  if (Number.isNaN(value)) {
    return 0;
  }

  return Math.max(0, Math.min(100, value));
}
