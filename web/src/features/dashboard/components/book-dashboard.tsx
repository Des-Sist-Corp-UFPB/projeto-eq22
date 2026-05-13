"use client";

import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { useBookDashboard } from "@/features/dashboard/api/dashboard-hooks";
import type { BookDashboardResponse, EntityUsageResponse, PovStatsResponse } from "@/features/dashboard/types";
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

  return (
    <div className="grid gap-4">
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <MetricCard label="Total de palavras" value={formatNumber(dashboard.totalWordCount)} />
        <MetricCard label="Total de cenas" value={formatNumber(dashboard.totalScenes)} />
        <MetricCard
          label="Cenas planejadas"
          value={`${formatNumber(dashboard.planningProgress.plannedScenesCount)} / ${formatNumber(dashboard.totalScenes)}`}
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
            <h2 className="text-base font-semibold text-zinc-950">Progresso de planejamento</h2>
            <p className="mt-1 text-sm text-zinc-500">
              {formatNumber(dashboard.planningProgress.plannedScenesCount)} de {formatNumber(dashboard.totalScenes)} cenas
              com POV, objetivo, conflito e resultado.
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
              <div key={status.status} className="rounded-md border border-zinc-200 bg-zinc-50 p-3">
                <div className="flex items-center justify-between gap-3">
                  <span className="text-sm font-medium text-zinc-800">{statusLabels[status.status]}</span>
                  <Badge variant="outline">{formatNumber(status.scenesCount)} cenas</Badge>
                </div>
                <p className="mt-2 text-xs text-zinc-500">{formatNumber(status.wordCount)} palavras</p>
              </div>
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

function clampPercent(value: number) {
  if (Number.isNaN(value)) {
    return 0;
  }

  return Math.max(0, Math.min(100, value));
}
