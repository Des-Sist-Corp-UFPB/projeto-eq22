"use client";

import { useEffect, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { Input } from "@/components/ui/input";
import { updateBook } from "@/features/books/api/books-api";
import type { WritingProgressPeriod } from "@/features/dashboard/api/dashboard-api";
import { useBookDashboard } from "@/features/dashboard/api/dashboard-hooks";
import {
  DashboardDetailModal,
  type DashboardDetailTarget,
  type DashboardWorkspaceTab,
} from "@/features/dashboard/components/dashboard-detail-modal";
import { DashboardMetricCard } from "@/features/dashboard/components/dashboard-metric-card";
import { DashboardStatusCard } from "@/features/dashboard/components/dashboard-status-card";
import type {
  BookDashboardResponse,
  EntityUsageResponse,
  PovStatsResponse,
} from "@/features/dashboard/types";
import { ApiError } from "@/lib/api/client";
import { queryKeys } from "@/lib/query/keys";

type BookDashboardProps = {
  bookId: string;
  onOpenSceneInEditor?: (sceneId: string) => void;
  onOpenWorkspaceTab?: (tab: DashboardWorkspaceTab) => void;
};

export function BookDashboard({ bookId, onOpenSceneInEditor, onOpenWorkspaceTab }: BookDashboardProps) {
  const [progressPeriod, setProgressPeriod] = useState<WritingProgressPeriod>("7d");
  const dashboardQuery = useBookDashboard(bookId, progressPeriod);

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

        {dashboardQuery.data ? (
          <DashboardContent
            dashboard={dashboardQuery.data}
            progressPeriod={progressPeriod}
            onProgressPeriodChange={setProgressPeriod}
            onOpenSceneInEditor={onOpenSceneInEditor}
            onOpenWorkspaceTab={onOpenWorkspaceTab}
          />
        ) : null}
      </div>
    </section>
  );
}

function DashboardContent({
  dashboard,
  progressPeriod,
  onProgressPeriodChange,
  onOpenSceneInEditor,
  onOpenWorkspaceTab,
}: {
  dashboard: BookDashboardResponse;
  progressPeriod: WritingProgressPeriod;
  onProgressPeriodChange: (period: WritingProgressPeriod) => void;
  onOpenSceneInEditor?: (sceneId: string) => void;
  onOpenWorkspaceTab?: (tab: DashboardWorkspaceTab) => void;
}) {
  const planningPercent = clampPercent(dashboard.planningProgress.plannedScenesPercent);
  const [detailTarget, setDetailTarget] = useState<DashboardDetailTarget | null>(null);

  function handleOpenSceneInEditor(sceneId: string) {
    onOpenSceneInEditor?.(sceneId);
    setDetailTarget(null);
  }

  function handleOpenWorkspaceTab(tab: DashboardWorkspaceTab) {
    onOpenWorkspaceTab?.(tab);
    setDetailTarget(null);
  }

  return (
    <div className="grid gap-4">
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <DashboardMetricCard label="Total de palavras" value={formatNumber(dashboard.totalWordCount)} />
        <DashboardMetricCard label="Total de cenas" value={formatNumber(dashboard.totalScenes)} />
        <DashboardMetricCard
          label="Planejamento completo"
          value={`${formatNumber(dashboard.planningProgress.plannedScenesCount)} / ${formatNumber(dashboard.totalScenes)}`}
          helper="POV, objetivo, conflito e resultado"
        />
        <DashboardMetricCard
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

      <WordTargetCard dashboard={dashboard} />
      <DailyWritingGoalCard
        dashboard={dashboard}
        progressPeriod={progressPeriod}
        onProgressPeriodChange={onProgressPeriodChange}
      />

      <Card className="p-4 transition-[transform,background-color,box-shadow] duration-150 ease-out hover:scale-[1.01] hover:bg-white hover:shadow-sm hover:shadow-zinc-200/70">
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
              <DashboardStatusCard
                key={status.status}
                status={status}
                onOpen={() => setDetailTarget({ type: "status", status: status.status })}
              />
            ))}
          </div>
        </Card>

        <Card className="p-4">
          <SectionHeader title="Lacunas narrativas" description="Pontos que talvez mereçam revisão." />
          <div className="mt-4 grid gap-2 sm:grid-cols-2 xl:grid-cols-1">
            <GapCard
              label="Sem POV"
              value={dashboard.narrativeGaps.scenesWithoutPov}
              onOpen={() => setDetailTarget({ type: "gap", gap: "withoutPov" })}
            />
            <GapCard
              label="Sem objetivo"
              value={dashboard.narrativeGaps.scenesWithoutGoal}
              onOpen={() => setDetailTarget({ type: "gap", gap: "withoutGoal" })}
            />
            <GapCard
              label="Sem conflito"
              value={dashboard.narrativeGaps.scenesWithoutConflict}
              onOpen={() => setDetailTarget({ type: "gap", gap: "withoutConflict" })}
            />
            <GapCard
              label="Sem resultado"
              value={dashboard.narrativeGaps.scenesWithoutOutcome}
              onOpen={() => setDetailTarget({ type: "gap", gap: "withoutOutcome" })}
            />
            <GapCard
              label="Sem localização"
              value={dashboard.narrativeGaps.scenesWithoutMainLocation}
              onOpen={() => setDetailTarget({ type: "gap", gap: "withoutMainLocation" })}
            />
            <GapCard
              label="Sem participantes"
              value={dashboard.narrativeGaps.scenesWithoutParticipants}
              onOpen={() => setDetailTarget({ type: "gap", gap: "withoutParticipants" })}
            />
          </div>
        </Card>
      </section>

      <section className="grid gap-4 xl:grid-cols-2">
        <Card className="p-4">
          <SectionHeader title="POV" description="Cenas e palavras por ponto de vista." />
          <div className="mt-4">
            <PovList items={dashboard.povStats} onOpenCharacter={(characterId) => setDetailTarget({ type: "character", id: characterId })} />
          </div>
        </Card>

        <div className="grid gap-4">
          <UsageCard
            title="Personagens mais usados"
            items={dashboard.mostUsedCharacters}
            emptyMessage="Nenhum participante vinculado."
            onOpenEntity={(id) => setDetailTarget({ type: "character", id })}
          />
          <UsageCard
            title="Localizações mais usadas"
            items={dashboard.mostUsedLocations}
            emptyMessage="Nenhuma localização vinculada."
            onOpenEntity={(id) => setDetailTarget({ type: "location", id })}
          />
          <UsageCard
            title="Itens mais usados"
            items={dashboard.mostUsedItems}
            emptyMessage="Nenhum item vinculado."
            onOpenEntity={(id) => setDetailTarget({ type: "item", id })}
          />
        </div>
      </section>

      {detailTarget ? (
        <DashboardDetailModal
          dashboard={dashboard}
          target={detailTarget}
          onClose={() => setDetailTarget(null)}
          onTargetChange={setDetailTarget}
          onOpenSceneInEditor={handleOpenSceneInEditor}
          onOpenWorkspaceTab={handleOpenWorkspaceTab}
        />
      ) : null}
    </div>
  );
}

function DailyWritingGoalCard({
  dashboard,
  progressPeriod,
  onProgressPeriodChange,
}: {
  dashboard: BookDashboardResponse;
  progressPeriod: WritingProgressPeriod;
  onProgressPeriodChange: (period: WritingProgressPeriod) => void;
}) {
  const queryClient = useQueryClient();
  const today = dashboard.writingProgress.today;
  const currentDailyTargetWordCount = dashboard.dailyTargetWordCount ?? today.dailyTargetWordCount;
  const [isEditing, setIsEditing] = useState(false);
  const [savedTargetValue, setSavedTargetValue] = useState<number | null>(currentDailyTargetWordCount ?? null);
  const [targetValue, setTargetValue] = useState(currentDailyTargetWordCount?.toString() ?? "");
  const [validationMessage, setValidationMessage] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  const updateTargetMutation = useMutation({
    mutationFn: (dailyTargetWordCount: number | null) => updateBook(dashboard.bookId, { dailyTargetWordCount }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.bookDashboard(dashboard.bookId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.book(dashboard.bookId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.books });
    },
  });

  useEffect(() => {
    setSavedTargetValue(currentDailyTargetWordCount ?? null);
    setTargetValue(currentDailyTargetWordCount?.toString() ?? "");
  }, [currentDailyTargetWordCount]);

  function startEditing() {
    setValidationMessage(null);
    setSuccessMessage(null);
    setIsEditing(true);
  }

  function cancelEditing() {
    setValidationMessage(null);
    setTargetValue(currentDailyTargetWordCount?.toString() ?? "");
    setIsEditing(false);
  }

  function saveTarget() {
    const parsedValue = Number(targetValue);

    setValidationMessage(null);
    setSuccessMessage(null);

    if (!Number.isInteger(parsedValue) || parsedValue <= 0) {
      setValidationMessage("Informe uma meta diária maior que zero.");
      return;
    }

    updateTargetMutation.mutate(parsedValue, {
      onSuccess: () => {
        setSavedTargetValue(parsedValue);
        setSuccessMessage("Meta diária salva.");
        setIsEditing(false);
      },
    });
  }

  function removeTarget() {
    setValidationMessage(null);
    setSuccessMessage(null);
    updateTargetMutation.mutate(null, {
      onSuccess: () => {
        setSavedTargetValue(null);
        setSuccessMessage("Meta diária removida.");
        setIsEditing(false);
      },
    });
  }

  const errorMessage = getBookTargetErrorMessage(updateTargetMutation.error);
  const effectiveDailyTargetWordCount = savedTargetValue;
  const hasTarget = effectiveDailyTargetWordCount != null;
  const progressPercent = hasTarget ? (today.netWordCountChange * 100.0) / effectiveDailyTargetWordCount : (today.progressPercent ?? 0);
  const visualProgressPercent = clampPercent(progressPercent);

  return (
    <Card className="p-4 transition-[transform,background-color,box-shadow] duration-150 ease-out hover:scale-[1.01] hover:bg-white hover:shadow-sm hover:shadow-zinc-200/70">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-base font-semibold text-zinc-950">Meta diária</h2>
          <p className="mt-1 text-sm text-zinc-500">Acompanhe o avanço de escrita registrado hoje.</p>
        </div>
        {!isEditing ? (
          <Button type="button" variant="secondary" size="sm" onClick={startEditing}>
            {hasTarget ? "Editar meta diária" : "Definir meta diária"}
          </Button>
        ) : null}
      </div>

      {!hasTarget && !isEditing ? (
        <div className="mt-4 rounded-md border border-dashed border-zinc-300 bg-zinc-50 p-4">
          <p className="text-sm font-medium text-zinc-900">Nenhuma meta diária definida.</p>
          <p className="mt-1 text-sm text-zinc-500">
            Defina uma meta opcional para acompanhar o progresso de hoje e dos últimos dias.
          </p>
          <p className="mt-3 text-sm text-zinc-700">Hoje: {formatSignedWords(today.netWordCountChange)}</p>
        </div>
      ) : null}

      {hasTarget && !isEditing ? (
        <div className="mt-4 grid gap-3">
          <div className="flex flex-wrap items-end justify-between gap-3">
            <div>
              <p className="text-2xl font-semibold text-zinc-950">
                Hoje: {formatSignedNumber(today.netWordCountChange)} / {formatNumber(effectiveDailyTargetWordCount ?? 0)} palavras
              </p>
              <p className="mt-1 text-sm text-zinc-500">{formatPercent(progressPercent)} da meta diária</p>
            </div>
            <Badge variant="outline">{formatDashboardDate(today.date)}</Badge>
          </div>
          <div className="h-3 overflow-hidden rounded-full bg-zinc-100">
            <div className="h-full rounded-full bg-emerald-500" style={{ width: `${visualProgressPercent}%` }} />
          </div>
        </div>
      ) : null}

      {isEditing ? (
        <div className="mt-4 grid gap-3 rounded-md border border-zinc-200 bg-zinc-50 p-3">
          <label className="grid gap-1 text-sm font-medium text-zinc-900">
            Meta diária de palavras
            <Input
              type="number"
              min={1}
              step={1}
              value={targetValue}
              onChange={(event) => setTargetValue(event.target.value)}
              placeholder="Ex.: 1000"
              disabled={updateTargetMutation.isPending}
            />
          </label>
          <div className="flex flex-wrap gap-2">
            <Button type="button" size="sm" onClick={saveTarget} disabled={updateTargetMutation.isPending}>
              {updateTargetMutation.isPending ? "Salvando..." : "Salvar meta diária"}
            </Button>
            {hasTarget ? (
              <Button type="button" variant="secondary" size="sm" onClick={removeTarget} disabled={updateTargetMutation.isPending}>
                Remover meta diária
              </Button>
            ) : null}
            <Button type="button" variant="ghost" size="sm" onClick={cancelEditing} disabled={updateTargetMutation.isPending}>
              Cancelar
            </Button>
          </div>
        </div>
      ) : null}

      <DailyProgressChart
        recentDays={dashboard.writingProgress.recentDays}
        dailyTargetWordCount={effectiveDailyTargetWordCount}
        progressPeriod={progressPeriod}
        onProgressPeriodChange={onProgressPeriodChange}
      />

      {validationMessage ? <FeedbackMessage variant="error" className="mt-3">{validationMessage}</FeedbackMessage> : null}
      {errorMessage ? <FeedbackMessage variant="error" className="mt-3">{errorMessage}</FeedbackMessage> : null}
      {successMessage ? <FeedbackMessage variant="success" className="mt-3">{successMessage}</FeedbackMessage> : null}
    </Card>
  );
}

function DailyProgressChart({
  recentDays,
  dailyTargetWordCount,
  progressPeriod,
  onProgressPeriodChange,
}: {
  recentDays: BookDashboardResponse["writingProgress"]["recentDays"];
  dailyTargetWordCount: number | null;
  progressPeriod: WritingProgressPeriod;
  onProgressPeriodChange: (period: WritingProgressPeriod) => void;
}) {
  const selectedPeriod = WRITING_PROGRESS_PERIODS.find((period) => period.value === progressPeriod) ?? WRITING_PROGRESS_PERIODS[0];
  const chartEntries = buildWritingProgressChartEntries(recentDays, progressPeriod);
  const positiveWordCounts = chartEntries.map((entry) => Math.max(entry.netWordCountChange, 0));
  const maxRecentWordCount = Math.max(0, ...positiveWordCounts);
  const chartReference = dailyTargetWordCount && dailyTargetWordCount > 0 ? Math.max(dailyTargetWordCount, maxRecentWordCount) : maxRecentWordCount;
  const goalLineTop = dailyTargetWordCount && dailyTargetWordCount > 0 && chartReference > 0
    ? 100 - clampPercent((dailyTargetWordCount * 100) / chartReference)
    : null;
  const totalWords = recentDays.reduce((total, day) => total + day.netWordCountChange, 0);
  const writingDays = recentDays.filter((day) => day.netWordCountChange !== 0).length;
  const averageWords = recentDays.length === 0 ? 0 : Math.round(totalWords / recentDays.length);
  const bestDay = getBestWritingDay(recentDays);
  const goalHitDays = dailyTargetWordCount && dailyTargetWordCount > 0
    ? recentDays.filter((day) => day.netWordCountChange >= dailyTargetWordCount).length
    : null;
  const periodLabel = getWritingProgressPeriodLabel(chartEntries, selectedPeriod.description, progressPeriod);
  const minBarWidth = progressPeriod === "7d" ? "3rem" : progressPeriod === "15d" ? "2.25rem" : progressPeriod === "30d" ? "1.75rem" : "3.5rem";
  const labelStep = chartEntries.length <= 15 ? 1 : chartEntries.length <= 30 ? 3 : 1;

  return (
    <section className="mt-4 rounded-md border border-zinc-200 bg-white p-3">
      <div className="flex flex-wrap items-start justify-between gap-3 border-b border-zinc-100 pb-3">
        <div>
          <h3 className="text-sm font-semibold text-zinc-950">Escrita no período</h3>
          <p className="mt-1 text-xs text-zinc-500">{periodLabel}</p>
          {dailyTargetWordCount && dailyTargetWordCount > 0 ? (
            <p className="mt-1 text-xs text-zinc-500">Meta diária: {formatNumber(dailyTargetWordCount)} palavras</p>
          ) : null}
        </div>
        <div className="flex flex-wrap gap-1 rounded-md border border-zinc-200 bg-zinc-50 p-1" aria-label="Período do progresso diário">
          {WRITING_PROGRESS_PERIODS.map((period) => (
            <Button
              key={period.value}
              type="button"
              variant={period.value === progressPeriod ? "primary" : "secondary"}
              size="sm"
              onClick={() => onProgressPeriodChange(period.value)}
            >
              {period.label}
            </Button>
          ))}
        </div>
      </div>

      <div className="mt-3 grid gap-3 lg:grid-cols-[minmax(0,1fr)_220px]">
        {chartEntries.length === 0 ? (
          <div className="rounded-md border border-dashed border-zinc-300 bg-zinc-50 p-3">
            <p className="text-sm font-medium text-zinc-900">Nenhum progresso recente registrado.</p>
            <p className="mt-1 text-sm text-zinc-500">Salve conteúdo de uma cena para acompanhar os últimos dias aqui.</p>
          </div>
        ) : (
          <div
            role="img"
            aria-label={`Gráfico vertical de escrita no período em ${selectedPeriod.label}`}
            className="overflow-x-auto pb-1"
          >
            <div className="flex h-40 min-w-full items-end gap-2 rounded-md border border-zinc-200 bg-zinc-50 px-3 py-3">
              {chartEntries.map((entry, index) => {
                const positiveWordCount = Math.max(entry.netWordCountChange, 0);
                const barPercent = chartReference > 0 ? clampPercent((positiveWordCount * 100) / chartReference) : 0;
                const barHeightPercent = Math.max(barPercent, positiveWordCount > 0 ? 8 : 1);
                const showDateLabel = index % labelStep === 0 || index === chartEntries.length - 1;

                return (
                  <div key={entry.key} className="flex h-full flex-1 flex-col items-center justify-end gap-1" style={{ minWidth: minBarWidth }}>
                    <div className="relative flex h-24 w-full items-end justify-center border-b border-zinc-300">
                      {goalLineTop != null ? (
                        <span aria-hidden="true" className="absolute left-0 right-0 border-t border-dashed border-emerald-300" style={{ top: `${goalLineTop}%` }} />
                      ) : null}
                      <div
                        role="presentation"
                        data-testid="daily-progress-vertical-bar"
                        aria-label={`${entry.accessibleLabel}: ${formatSignedWords(entry.netWordCountChange)}`}
                        className={`w-4 max-w-full rounded-t-sm ${entry.netWordCountChange < 0 ? "bg-zinc-300" : "bg-emerald-500"}`}
                        style={{ height: `${barHeightPercent}%` }}
                      />
                    </div>
                    <p className="h-4 text-center text-[11px] tabular-nums leading-tight text-zinc-600">{formatSignedNumber(entry.netWordCountChange)}</p>
                    <p className="h-4 text-center text-[11px] font-medium leading-tight text-zinc-700">{showDateLabel ? entry.axisLabel : ""}</p>
                  </div>
                );
              })}
            </div>
          </div>
        )}

        <div className="rounded-md border border-zinc-200 bg-zinc-50 p-3">
          <h4 className="text-sm font-semibold text-zinc-950">Resumo do período</h4>
          <dl className="mt-3 grid gap-2">
            <SummaryMetric label="Total no período" value={formatSignedWords(totalWords)} />
            <SummaryMetric label="Média por dia" value={formatSignedWords(averageWords)} />
            <SummaryMetric label="Dias com escrita" value={formatNumber(writingDays)} />
            <SummaryMetric
              label="Melhor dia"
              value={bestDay ? formatSignedWords(bestDay.netWordCountChange) : "0 palavras"}
              detail={bestDay ? formatDashboardDate(bestDay.date) : undefined}
            />
            <SummaryMetric label="Dias em que bateu a meta" value={goalHitDays == null ? "Sem meta" : formatNumber(goalHitDays)} />
          </dl>
        </div>
      </div>
    </section>
  );
}

function SummaryMetric({ label, value, detail }: { label: string; value: string; detail?: string }) {
  return (
    <div className="rounded-md border border-zinc-200 bg-white px-3 py-2">
      <dt className="text-xs text-zinc-500">{label}</dt>
      <dd className="mt-1 text-sm font-semibold tabular-nums text-zinc-950">{value}</dd>
      {detail ? <dd className="mt-0.5 text-xs text-zinc-500">{detail}</dd> : null}
    </div>
  );
}

type WritingProgressChartEntry = {
  key: string;
  axisLabel: string;
  accessibleLabel: string;
  periodStart: string;
  periodEnd: string;
  netWordCountChange: number;
};

function buildWritingProgressChartEntries(
  recentDays: BookDashboardResponse["writingProgress"]["recentDays"],
  progressPeriod: WritingProgressPeriod
): WritingProgressChartEntry[] {
  const chronologicalDays = [...recentDays].sort((first, second) => first.date.localeCompare(second.date));

  if (progressPeriod !== "3m" && progressPeriod !== "6m") {
    return chronologicalDays.map((day) => ({
      key: day.date,
      axisLabel: formatDashboardDate(day.date, progressPeriod === "30d"),
      accessibleLabel: formatDashboardDate(day.date),
      periodStart: day.date,
      periodEnd: day.date,
      netWordCountChange: day.netWordCountChange,
    }));
  }

  const monthlyEntries = new Map<string, WritingProgressChartEntry>();
  for (const day of chronologicalDays) {
    const [year, month] = day.date.split("-");
    const key = `${year}-${month}`;
    const existingEntry = monthlyEntries.get(key);
    if (existingEntry) {
      existingEntry.periodEnd = day.date;
      existingEntry.netWordCountChange += day.netWordCountChange;
      continue;
    }

    monthlyEntries.set(key, {
      key,
      axisLabel: formatDashboardMonth(day.date, false),
      accessibleLabel: formatDashboardMonth(day.date, true),
      periodStart: day.date,
      periodEnd: day.date,
      netWordCountChange: day.netWordCountChange,
    });
  }

  return Array.from(monthlyEntries.values());
}

function getWritingProgressPeriodLabel(
  entries: WritingProgressChartEntry[],
  fallback: string,
  progressPeriod: WritingProgressPeriod
) {
  if (entries.length === 0) {
    return fallback;
  }

  const firstEntry = entries[0];
  const lastEntry = entries[entries.length - 1];
  if (progressPeriod === "3m" || progressPeriod === "6m") {
    if (firstEntry.key === lastEntry.key) {
      return formatDashboardMonth(firstEntry.periodStart, true);
    }

    return `${formatDashboardMonth(firstEntry.periodStart, true)} - ${formatDashboardMonth(lastEntry.periodEnd, true)}`;
  }

  if (firstEntry.periodStart === lastEntry.periodEnd) {
    return formatDashboardDate(firstEntry.periodStart);
  }

  return `${formatDashboardDate(firstEntry.periodStart)} - ${formatDashboardDate(lastEntry.periodEnd)}`;
}

function getBestWritingDay(recentDays: BookDashboardResponse["writingProgress"]["recentDays"]) {
  if (recentDays.length === 0) {
    return null;
  }

  return recentDays.reduce((bestDay, day) => (day.netWordCountChange > bestDay.netWordCountChange ? day : bestDay));
}

const WRITING_PROGRESS_PERIODS: Array<{ value: WritingProgressPeriod; label: string; description: string }> = [
  { value: "7d", label: "7 dias", description: "Últimos 7 dias" },
  { value: "15d", label: "15 dias", description: "Últimos 15 dias" },
  { value: "30d", label: "30 dias", description: "Últimos 30 dias" },
  { value: "3m", label: "3 meses", description: "Últimos 3 meses" },
  { value: "6m", label: "6 meses", description: "Últimos 6 meses" },
];

function WordTargetCard({ dashboard }: { dashboard: BookDashboardResponse }) {
  const queryClient = useQueryClient();
  const [isEditing, setIsEditing] = useState(false);
  const [targetValue, setTargetValue] = useState(dashboard.targetWordCount?.toString() ?? "");
  const [validationMessage, setValidationMessage] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  const updateTargetMutation = useMutation({
    mutationFn: (targetWordCount: number | null) => updateBook(dashboard.bookId, { targetWordCount }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.bookDashboard(dashboard.bookId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.book(dashboard.bookId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.books });
    },
  });

  useEffect(() => {
    setTargetValue(dashboard.targetWordCount?.toString() ?? "");
  }, [dashboard.targetWordCount]);

  function startEditing() {
    setValidationMessage(null);
    setSuccessMessage(null);
    setIsEditing(true);
  }

  function cancelEditing() {
    setValidationMessage(null);
    setTargetValue(dashboard.targetWordCount?.toString() ?? "");
    setIsEditing(false);
  }

  function saveTarget() {
    const parsedValue = Number(targetValue);

    setValidationMessage(null);
    setSuccessMessage(null);

    if (!Number.isInteger(parsedValue) || parsedValue <= 0) {
      setValidationMessage("Informe uma meta maior que zero.");
      return;
    }

    updateTargetMutation.mutate(parsedValue, {
      onSuccess: () => {
        setSuccessMessage("Meta de palavras salva.");
        setIsEditing(false);
      },
    });
  }

  function removeTarget() {
    setValidationMessage(null);
    setSuccessMessage(null);
    updateTargetMutation.mutate(null, {
      onSuccess: () => {
        setSuccessMessage("Meta de palavras removida.");
        setIsEditing(false);
      },
    });
  }

  const errorMessage = getBookTargetErrorMessage(updateTargetMutation.error);
  const hasTarget = dashboard.targetWordCount != null;
  const progressPercent = dashboard.wordCountProgressPercent ?? 0;
  const visualProgressPercent = clampPercent(progressPercent);

  return (
    <Card className="p-4 transition-[transform,background-color,box-shadow] duration-150 ease-out hover:scale-[1.01] hover:bg-white hover:shadow-sm hover:shadow-zinc-200/70">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-base font-semibold text-zinc-950">Meta de palavras</h2>
          <p className="mt-1 text-sm text-zinc-500">Referência editorial opcional para acompanhar o tamanho do livro.</p>
        </div>
        {!isEditing ? (
          <Button type="button" variant="secondary" size="sm" onClick={startEditing}>
            {hasTarget ? "Editar meta" : "Definir meta"}
          </Button>
        ) : null}
      </div>

      {!hasTarget && !isEditing ? (
        <div className="mt-4 rounded-md border border-dashed border-zinc-300 bg-zinc-50 p-4">
          <p className="text-sm font-medium text-zinc-900">Nenhuma meta de palavras definida.</p>
          <p className="mt-1 text-sm text-zinc-500">
            Defina uma referência opcional para acompanhar o progresso do livro.
          </p>
        </div>
      ) : null}

      {hasTarget && !isEditing ? (
        <div className="mt-4 grid gap-3">
          <div className="flex flex-wrap items-end justify-between gap-3">
            <div>
              <p className="text-2xl font-semibold text-zinc-950">
                {formatNumber(dashboard.totalWordCount)} / {formatNumber(dashboard.targetWordCount ?? 0)} palavras
              </p>
              <p className="mt-1 text-sm text-zinc-500">{formatPercent(progressPercent)} da meta de palavras</p>
            </div>
            {dashboard.exceededTargetWordCount && dashboard.exceededTargetWordCount > 0 ? (
              <Badge className="bg-amber-100 text-amber-900">
                Meta ultrapassada em {formatNumber(dashboard.exceededTargetWordCount)} palavras
              </Badge>
            ) : (
              <Badge variant="outline">{formatNumber(dashboard.remainingWordCount ?? 0)} palavras restantes</Badge>
            )}
          </div>
          <div className="h-3 overflow-hidden rounded-full bg-zinc-100">
            <div className="h-full rounded-full bg-sky-500" style={{ width: `${visualProgressPercent}%` }} />
          </div>
        </div>
      ) : null}

      {isEditing ? (
        <div className="mt-4 grid gap-3 rounded-md border border-zinc-200 bg-zinc-50 p-3">
          <label className="grid gap-1 text-sm font-medium text-zinc-900">
            Meta opcional de palavras
            <Input
              type="number"
              min={1}
              step={1}
              value={targetValue}
              onChange={(event) => setTargetValue(event.target.value)}
              placeholder="Ex.: 70000"
              disabled={updateTargetMutation.isPending}
            />
          </label>
          <div className="flex flex-wrap gap-2">
            <Button type="button" size="sm" onClick={saveTarget} disabled={updateTargetMutation.isPending}>
              {updateTargetMutation.isPending ? "Salvando..." : "Salvar meta"}
            </Button>
            {hasTarget ? (
              <Button type="button" variant="secondary" size="sm" onClick={removeTarget} disabled={updateTargetMutation.isPending}>
                Remover meta
              </Button>
            ) : null}
            <Button type="button" variant="ghost" size="sm" onClick={cancelEditing} disabled={updateTargetMutation.isPending}>
              Cancelar
            </Button>
          </div>
        </div>
      ) : null}

      {validationMessage ? <FeedbackMessage variant="error" className="mt-3">{validationMessage}</FeedbackMessage> : null}
      {errorMessage ? <FeedbackMessage variant="error" className="mt-3">{errorMessage}</FeedbackMessage> : null}
      {successMessage ? <FeedbackMessage variant="success" className="mt-3">{successMessage}</FeedbackMessage> : null}
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

function GapCard({ label, value, onOpen }: { label: string; value: number; onOpen: () => void }) {
  const hasGap = value > 0;

  return (
    <button
      type="button"
      onClick={onOpen}
      className={`flex items-center justify-between gap-3 rounded-md border p-3 text-left transition-[transform,background-color,box-shadow] duration-150 ease-out hover:-translate-y-0.5 hover:shadow-sm ${
        hasGap ? "border-amber-200 bg-amber-50 text-amber-950" : "border-emerald-200 bg-emerald-50 text-emerald-950"
      }`}
    >
      <span className="text-sm font-medium">{label}</span>
      <span className="text-sm font-semibold">{formatNumber(value)}</span>
    </button>
  );
}

function PovList({
  items,
  onOpenCharacter,
}: {
  items: PovStatsResponse[];
  onOpenCharacter: (characterId: string) => void;
}) {
  if (items.length === 0) {
    return <EmptyState title="Nenhum POV vinculado." size="sm" />;
  }

  return (
    <ol className="grid gap-2">
      {items.map((item, index) => (
        <li
          key={item.characterId}
        >
          <button
            type="button"
            onClick={() => onOpenCharacter(item.characterId)}
            className="flex w-full cursor-pointer items-center justify-between gap-3 rounded-md border border-zinc-200 bg-zinc-50 p-3 text-left transition-[transform,background-color,box-shadow] duration-150 ease-out hover:-translate-y-0.5 hover:scale-[1.01] hover:bg-white hover:shadow-sm hover:shadow-zinc-200/70"
          >
            <div className="min-w-0">
              <p className="truncate text-sm font-medium text-zinc-900">
                {index + 1}. {item.name}
              </p>
              <p className="mt-1 text-xs text-zinc-500">{formatNumber(item.wordCount)} palavras</p>
            </div>
            <Badge variant="outline">{formatNumber(item.scenesCount)} cenas</Badge>
          </button>
        </li>
      ))}
    </ol>
  );
}

function UsageCard({
  title,
  items,
  emptyMessage,
  onOpenEntity,
}: {
  title: string;
  items: EntityUsageResponse[];
  emptyMessage: string;
  onOpenEntity: (id: string) => void;
}) {
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
              >
                <button
                  type="button"
                  onClick={() => onOpenEntity(item.id)}
                  className="flex w-full cursor-pointer items-center justify-between gap-3 rounded-md border border-zinc-200 bg-zinc-50 p-3 text-left transition-[transform,background-color,box-shadow] duration-150 ease-out hover:-translate-y-0.5 hover:scale-[1.01] hover:bg-white hover:shadow-sm hover:shadow-zinc-200/70"
                >
                  <span className="min-w-0 truncate text-sm font-medium text-zinc-900">
                    {index + 1}. {item.name}
                  </span>
                  <Badge variant="outline">{formatNumber(item.scenesCount)} cenas</Badge>
                </button>
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

function formatSignedWords(value: number) {
  return `${formatSignedNumber(value)} palavras`;
}

function formatSignedNumber(value: number) {
  const formattedValue = formatNumber(Math.abs(value));
  if (value < 0) {
    return `-${formattedValue}`;
  }

  return formattedValue;
}

function formatDashboardDate(value: string, compact = false) {
  const [year, month, day] = value.split("-").map(Number);
  if (!year || !month || !day) {
    return value;
  }

  return new Intl.DateTimeFormat("pt-BR", compact ? { day: "2-digit" } : { day: "2-digit", month: "2-digit" }).format(new Date(year, month - 1, day));
}

function formatDashboardMonth(value: string, includeYear: boolean) {
  const [year, month] = value.split("-").map(Number);
  if (!year || !month) {
    return value;
  }

  const monthLabel = new Intl.DateTimeFormat("pt-BR", { month: "short" }).format(new Date(year, month - 1, 1));
  return includeYear ? `${monthLabel}/${year}` : monthLabel;
}

function clampPercent(value: number) {
  if (Number.isNaN(value)) {
    return 0;
  }

  return Math.max(0, Math.min(100, value));
}

function getBookTargetErrorMessage(error: unknown) {
  if (!error) {
    return null;
  }

  if (error instanceof ApiError) {
    return error.message || "Não foi possível salvar a meta agora.";
  }

  return "Não foi possível salvar a meta agora.";
}
