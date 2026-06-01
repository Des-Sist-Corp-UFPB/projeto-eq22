"use client";

import { useMemo } from "react";
import { Badge } from "@/components/ui/badge";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import type { BookOutline } from "@/features/outline/types";
import {
  buildStoryboardModel,
  NO_POV_LANE_ID,
  type StoryboardChapter,
  type StoryboardLane,
  type StoryboardModel,
  type StoryboardScenePlacement,
} from "@/features/storyboard/utils/storyboard-model";
import type { SceneStatus } from "@/features/scenes/types";

type SceneStoryboardPanelProps = {
  outline?: BookOutline | null;
  isLoading: boolean;
  isError: boolean;
  onOpenSceneInEditor: (sceneId: string) => void;
};

const STATUS_LABELS: Record<SceneStatus, string> = {
  IDEA: "Ideia",
  PLANNED: "Planejada",
  DRAFT: "Rascunho",
  WRITTEN: "Escrita",
  REVISED: "Revisada",
  FINAL: "Final",
};

export function SceneStoryboardPanel({
  outline,
  isLoading,
  isError,
  onOpenSceneInEditor,
}: SceneStoryboardPanelProps) {
  const model = useMemo(() => (outline ? buildStoryboardModel(outline) : null), [outline]);

  if (isLoading) {
    return (
      <section className="h-full overflow-y-auto bg-zinc-50 p-4 md:p-6">
        <LoadingState label="Carregando storyboard..." />
      </section>
    );
  }

  if (isError) {
    return (
      <section className="h-full overflow-y-auto bg-zinc-50 p-4 md:p-6">
        <ErrorState message="Nao foi possivel carregar o storyboard." />
      </section>
    );
  }

  if (!outline || !model) {
    return (
      <section className="h-full overflow-y-auto bg-zinc-50 p-4 md:p-6">
        <EmptyState title="Storyboard indisponivel" description="Nao recebemos dados do outline deste livro." />
      </section>
    );
  }

  if (model.scenes.length === 0) {
    return (
      <section className="h-full overflow-y-auto bg-zinc-50 p-4 md:p-6">
        <div className="mx-auto grid max-w-6xl gap-4">
          <StoryboardHeader />
          <EmptyState
            title="Este livro ainda nao tem cenas."
            description="Crie cenas na aba Cenas para visualizar a sequencia narrativa no storyboard."
          />
        </div>
      </section>
    );
  }

  return (
    <section className="h-full overflow-y-auto bg-zinc-50 p-4 md:p-6">
      <div className="mx-auto grid max-w-7xl gap-4">
        <StoryboardHeader />
        <div className="hidden lg:block">
          <StoryboardCanvas model={model} onOpenSceneInEditor={onOpenSceneInEditor} />
        </div>
        <div className="lg:hidden">
          <StoryboardStackedView model={model} onOpenSceneInEditor={onOpenSceneInEditor} />
        </div>
      </div>
    </section>
  );
}

function StoryboardHeader() {
  return (
    <header>
      <h1 className="text-xl font-semibold text-zinc-950">Storyboard</h1>
      <p className="mt-1 text-sm text-zinc-500">
        Visualize a sequencia narrativa do manuscrito por capitulo e ponto de vista.
      </p>
    </header>
  );
}

function StoryboardCanvas({
  model,
  onOpenSceneInEditor,
}: {
  model: StoryboardModel;
  onOpenSceneInEditor: (sceneId: string) => void;
}) {
  const gridTemplateColumns = `10rem repeat(${model.chapters.length}, 16rem)`;
  const scenesByCell = groupScenesByLaneAndChapter(model.scenes);

  return (
    <div className="overflow-hidden rounded-lg border border-zinc-200 bg-white shadow-sm shadow-zinc-200/60">
      <div className="max-h-[calc(100vh-11rem)] overflow-auto">
        <div className="min-w-max">
          <div
            className="sticky top-0 z-30 grid border-b border-zinc-200 bg-zinc-50"
            style={{ gridTemplateColumns }}
          >
            <div className="sticky left-0 z-40 border-r border-zinc-200 bg-zinc-50 px-3 py-2 text-xs font-semibold uppercase text-zinc-500">
              Secoes
            </div>
            {model.sections.map((section) => (
              <div
                key={section.id}
                className="border-r border-zinc-200 px-3 py-2 text-xs font-semibold uppercase text-emerald-700"
                style={{ gridColumn: `${section.startChapterIndex + 2} / span ${section.chapterCount}` }}
              >
                <span className="line-clamp-1">{section.title}</span>
              </div>
            ))}
          </div>

          <div
            className="sticky top-9 z-20 grid border-b border-zinc-200 bg-white"
            style={{ gridTemplateColumns }}
          >
            <div className="sticky left-0 z-30 border-r border-zinc-200 bg-white px-3 py-3 text-xs font-semibold uppercase text-zinc-500">
              POV
            </div>
            {model.chapters.map((chapter) => (
              <div key={chapter.id} className="min-h-16 border-r border-zinc-200 px-3 py-3">
                <p className="text-[11px] font-medium uppercase text-zinc-500">Capitulo</p>
                <h2 className="mt-1 line-clamp-2 text-sm font-semibold text-zinc-950">{chapter.title}</h2>
              </div>
            ))}
          </div>

          {model.lanes.map((lane) => (
            <div
              key={lane.id}
              className="grid min-h-32 border-b border-zinc-200 last:border-b-0"
              style={{ gridTemplateColumns }}
            >
              <LaneHeader lane={lane} scenes={model.scenes.filter((scene) => scene.laneId === lane.id)} />
              {model.chapters.map((chapter) => {
                const scenes = scenesByCell.get(cellKey(lane.id, chapter.id)) ?? [];
                return (
                  <div key={chapter.id} className="grid content-start gap-2 border-r border-zinc-200 bg-zinc-50/60 p-2">
                    {scenes.map((scene) => (
                      <StoryboardSceneCard
                        key={scene.id}
                        placement={scene}
                        compact
                        onOpen={onOpenSceneInEditor}
                      />
                    ))}
                  </div>
                );
              })}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function LaneHeader({ lane, scenes }: { lane: StoryboardLane; scenes: StoryboardScenePlacement[] }) {
  return (
    <div className="sticky left-0 z-10 border-r border-zinc-200 bg-white px-3 py-3">
      <p className={`line-clamp-2 text-sm font-semibold ${lane.isNoPov ? "text-amber-900" : "text-zinc-950"}`}>
        {lane.label}
      </p>
      <p className="mt-1 text-xs text-zinc-500">{formatNumber(scenes.length)} cenas</p>
    </div>
  );
}

function StoryboardStackedView({
  model,
  onOpenSceneInEditor,
}: {
  model: StoryboardModel;
  onOpenSceneInEditor: (sceneId: string) => void;
}) {
  return (
    <div className="grid gap-4">
      {model.chapters.map((chapter) => {
        const chapterScenes = model.scenes.filter((scene) => scene.chapterId === chapter.id);
        return (
          <section key={chapter.id} className="rounded-lg border border-zinc-200 bg-white shadow-sm shadow-zinc-200/60">
            <header className="border-b border-zinc-200 bg-zinc-50 px-4 py-3">
              <p className="text-xs font-medium uppercase text-emerald-700">{chapter.sectionTitle}</p>
              <h2 className="mt-1 text-base font-semibold text-zinc-950">{chapter.title}</h2>
            </header>
            <div className="grid gap-3 p-3">
              {model.lanes.map((lane) => {
                const laneScenes = chapterScenes.filter((scene) => scene.laneId === lane.id);
                if (laneScenes.length === 0) {
                  return null;
                }

                return (
                  <div key={lane.id} className="grid gap-2">
                    <p className="text-xs font-semibold uppercase text-zinc-500">{lane.label}</p>
                    {laneScenes.map((scene) => (
                      <StoryboardSceneCard key={scene.id} placement={scene} onOpen={onOpenSceneInEditor} />
                    ))}
                  </div>
                );
              })}
            </div>
          </section>
        );
      })}
    </div>
  );
}

function StoryboardSceneCard({
  placement,
  compact = false,
  onOpen,
}: {
  placement: StoryboardScenePlacement;
  compact?: boolean;
  onOpen: (sceneId: string) => void;
}) {
  const scene = placement.scene;
  const gapCount = scene.planningGaps.length;
  const showPov = !compact || placement.laneId === NO_POV_LANE_ID;

  return (
    <button
      type="button"
      aria-label={`Abrir cena ${scene.title}`}
      onClick={() => onOpen(scene.id)}
      className="grid w-full gap-2 rounded-md border border-zinc-200 bg-white p-3 text-left shadow-sm shadow-zinc-200/50 transition-[transform,background-color,box-shadow,border-color] duration-150 ease-out hover:-translate-y-0.5 hover:border-emerald-300 hover:bg-emerald-50/30 hover:shadow-md focus:outline-none focus:ring-2 focus:ring-emerald-600 focus:ring-offset-2"
    >
      <div className="min-w-0">
        <h3 className="line-clamp-2 text-sm font-semibold text-zinc-950">{scene.title}</h3>
        <p className="mt-1 line-clamp-1 text-xs text-zinc-500">{placement.chapterTitle}</p>
      </div>

      <div className="flex flex-wrap gap-1.5">
        <Badge variant="outline">{STATUS_LABELS[scene.status]}</Badge>
        <Badge variant="neutral">{formatNumber(scene.wordCount)} palavras</Badge>
        {showPov ? <Badge variant="outline">{placement.laneLabel}</Badge> : null}
        {gapCount > 0 ? (
          <Badge className="bg-amber-100 text-amber-900">
            {formatNumber(gapCount)} {gapCount === 1 ? "lacuna" : "lacunas"}
          </Badge>
        ) : null}
      </div>
    </button>
  );
}

function groupScenesByLaneAndChapter(scenes: StoryboardScenePlacement[]) {
  const groups = new Map<string, StoryboardScenePlacement[]>();
  for (const scene of scenes) {
    const key = cellKey(scene.laneId, scene.chapterId);
    const group = groups.get(key) ?? [];
    group.push(scene);
    groups.set(key, group);
  }
  return groups;
}

function cellKey(laneId: string, chapterId: string) {
  return `${laneId}:${chapterId}`;
}

function formatNumber(value: number) {
  return new Intl.NumberFormat("pt-BR").format(value);
}
