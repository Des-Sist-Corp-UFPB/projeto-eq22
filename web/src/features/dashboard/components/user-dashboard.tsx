"use client";

import { useState } from "react";
import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { EmptyState } from "@/components/ui/empty-state";
import type { WritingProgressPeriod } from "@/features/dashboard/api/dashboard-api";
import { useCurrentUserDashboard } from "@/features/dashboard/api/dashboard-hooks";

const PERIODS: Array<{ value: WritingProgressPeriod; label: string }> = [
  { value: "7d", label: "7 dias" },
  { value: "15d", label: "15 dias" },
  { value: "30d", label: "30 dias" },
  { value: "3m", label: "3 meses" },
  { value: "6m", label: "6 meses" },
  { value: "12m", label: "12 meses" },
];

export function UserDashboard() {
  const [progressPeriod, setProgressPeriod] = useState<WritingProgressPeriod>("7d");
  const dashboardQuery = useCurrentUserDashboard(progressPeriod);

  return (
    <main className="min-h-screen bg-[#f7f7f2] px-5 py-8 text-zinc-950 md:px-8 md:py-12">
      <div className="mx-auto grid w-full max-w-6xl gap-6">
        <header className="flex flex-wrap items-start justify-between gap-4">
          <div className="grid gap-3">
            <Badge variant="outline">Dashboard</Badge>
            <div className="grid gap-2">
              <h1 className="text-4xl font-semibold tracking-normal text-zinc-950 md:text-5xl">Minha escrita</h1>
              <p className="max-w-2xl text-base leading-7 text-zinc-600">
                Uma visão global da sua escrita registrada nos livros do tenant atual.
              </p>
            </div>
          </div>
          <Link
            href="/"
            className="inline-flex min-h-9 items-center justify-center rounded-md border border-zinc-300 bg-white px-3 py-2 text-sm font-medium text-zinc-900 transition hover:bg-zinc-100"
          >
            Voltar para biblioteca
          </Link>
        </header>

        <div className="flex flex-wrap gap-1 rounded-md border border-zinc-200 bg-white p-1" aria-label="Período do dashboard">
          {PERIODS.map((period) => (
            <Button
              key={period.value}
              type="button"
              size="sm"
              variant={progressPeriod === period.value ? "primary" : "ghost"}
              onClick={() => setProgressPeriod(period.value)}
            >
              {period.label}
            </Button>
          ))}
        </div>

        {dashboardQuery.isLoading ? <LoadingState label="Carregando dashboard..." /> : null}
        {dashboardQuery.isError ? <ErrorState message="Não foi possível carregar seu dashboard." /> : null}

        {dashboardQuery.data ? (
          <section className="grid gap-4">
            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-6">
              <MetricCard label="Palavras produtivas" value={formatSignedNumber(dashboardQuery.data.summary.productiveWords)} />
              <MetricCard label="Ajustes do manuscrito" value={formatSignedNumber(dashboardQuery.data.summary.manuscriptAdjustments)} />
              <MetricCard label="Dias com escrita" value={formatNumber(dashboardQuery.data.summary.writingDays)} />
              <MetricCard label="Livros escritos" value={formatNumber(dashboardQuery.data.summary.booksWrittenIn)} />
              <MetricCard label="Sequência atual" value={`${formatNumber(dashboardQuery.data.summary.currentGlobalWritingStreak)} dias`} />
              <MetricCard label="Melhor sequência" value={`${formatNumber(dashboardQuery.data.summary.bestGlobalWritingStreak)} dias`} />
            </div>

            <Card className="p-4">
              <h2 className="text-base font-semibold text-zinc-950">Série diária</h2>
              <p className="mt-1 text-sm text-zinc-500">Datas de escrita armazenadas para o período selecionado.</p>
              <ol data-testid="user-dashboard-daily-series" className="mt-4 grid gap-2">
                {dashboardQuery.data.dailySeries
                  .filter((day) => day.productiveWords !== 0 || day.manuscriptAdjustments !== 0)
                  .map((day) => (
                    <li key={day.date} className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-zinc-200 bg-zinc-50 p-3 text-sm">
                      <span className="text-zinc-600">{formatDate(day.date)}</span>
                      <span className="font-medium text-zinc-950">
                        {formatSignedNumber(day.productiveWords)} produtivas
                        {day.manuscriptAdjustments !== 0 ? ` · ${formatSignedNumber(day.manuscriptAdjustments)} ajustes` : ""}
                      </span>
                    </li>
                  ))}
              </ol>
            </Card>

            <Card className="p-4">
              <h2 className="text-base font-semibold text-zinc-950">Livros com contribuição</h2>
              <p className="mt-1 text-sm text-zinc-500">
                Contribuições registradas entre {formatDate(dashboardQuery.data.period.startDate)} e {formatDate(dashboardQuery.data.period.endDate)}.
              </p>
              {dashboardQuery.data.bookContributions.length === 0 ? (
                <EmptyState title="Nenhuma escrita produtiva registrada neste período." size="sm" />
              ) : (
                <ol className="mt-4 grid gap-2">
                  {dashboardQuery.data.bookContributions.map((book) => (
                    <li key={book.bookId} data-testid="user-dashboard-book-contribution" className="rounded-md border border-zinc-200 bg-zinc-50 p-3">
                      <div className="flex flex-wrap items-center justify-between gap-3">
                        <Link href={`/books/${book.bookId}`} className="font-medium text-zinc-950 hover:underline">
                          {book.title}
                        </Link>
                        <span className="text-sm text-zinc-600">
                          {formatSignedNumber(book.productiveWords)} produtivas · {formatNumber(book.writingDays)} dias
                        </span>
                      </div>
                    </li>
                  ))}
                </ol>
              )}
            </Card>
          </section>
        ) : null}
      </div>
    </main>
  );
}

function MetricCard({ label, value }: { label: string; value: string }) {
  return (
    <Card className="p-4">
      <p className="text-sm text-zinc-500">{label}</p>
      <p className="mt-2 text-2xl font-semibold text-zinc-950">{value}</p>
    </Card>
  );
}

function formatNumber(value: number) {
  return new Intl.NumberFormat("pt-BR").format(value);
}

function formatSignedNumber(value: number) {
  const formatted = formatNumber(Math.abs(value));
  if (value > 0) {
    return `+${formatted}`;
  }
  if (value < 0) {
    return `-${formatted}`;
  }
  return formatted;
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat("pt-BR", { day: "2-digit", month: "short", year: "numeric", timeZone: "UTC" })
    .format(new Date(`${value}T00:00:00Z`));
}
