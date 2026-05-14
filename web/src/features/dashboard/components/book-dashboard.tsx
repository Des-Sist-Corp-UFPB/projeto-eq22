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
import { useBookDashboard } from "@/features/dashboard/api/dashboard-hooks";
import { DashboardDetailModal, type DashboardDetailTarget } from "@/features/dashboard/components/dashboard-detail-modal";
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
  const [detailTarget, setDetailTarget] = useState<DashboardDetailTarget | null>(null);

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
        />
      ) : null}
    </div>
  );
}

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
