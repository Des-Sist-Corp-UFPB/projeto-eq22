"use client";

import { FormEvent, useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getScene, updateScene, updateSceneContent } from "@/features/scenes/api/scenes-api";
import type { SceneStatus } from "@/features/scenes/types";
import { queryKeys } from "@/lib/query/keys";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { TextAreaField, TextField } from "@/components/ui/text-field";
import { Textarea } from "@/components/ui/textarea";

const SCENE_STATUSES: SceneStatus[] = ["IDEA", "PLANNED", "DRAFT", "WRITTEN", "REVISED", "FINAL"];

type SceneEditorProps = {
  bookId: string;
  sceneId: string | null;
};

export function SceneEditor({ bookId, sceneId }: SceneEditorProps) {
  const queryClient = useQueryClient();
  const [title, setTitle] = useState("");
  const [summary, setSummary] = useState("");
  const [status, setStatus] = useState<SceneStatus>("IDEA");
  const [contentText, setContentText] = useState("");

  const sceneQuery = useQuery({
    queryKey: sceneId ? queryKeys.scene(sceneId) : ["scenes", "empty"],
    queryFn: () => getScene(sceneId as string),
    enabled: Boolean(sceneId),
  });

  useEffect(() => {
    if (!sceneQuery.data) {
      return;
    }

    setTitle(sceneQuery.data.title);
    setSummary(sceneQuery.data.summary ?? "");
    setStatus(sceneQuery.data.status);
    setContentText(sceneQuery.data.contentText ?? "");
  }, [sceneQuery.data]);

  const metadataMutation = useMutation({
    mutationFn: () =>
      updateScene(sceneId as string, {
        title: title.trim(),
        summary: summary.trim(),
        status,
      }),
    onSuccess: (scene) => {
      void queryClient.setQueryData(queryKeys.scene(scene.id), scene);
      void queryClient.invalidateQueries({ queryKey: queryKeys.outline(bookId) });
    },
  });

  const contentMutation = useMutation({
    mutationFn: () =>
      updateSceneContent(sceneId as string, {
        contentText,
        contentJson: "",
      }),
    onSuccess: (scene) => {
      void queryClient.setQueryData(queryKeys.scene(scene.id), scene);
      void queryClient.invalidateQueries({ queryKey: queryKeys.outline(bookId) });
    },
  });

  function handleMetadataSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!sceneId || !title.trim()) {
      return;
    }

    metadataMutation.mutate();
  }

  if (!sceneId) {
    return (
      <section className="flex h-full overflow-y-auto p-6">
        <EmptyState
          className="m-auto max-w-md"
          title="Selecione uma cena"
          description="Selecione uma cena no esboço ou crie uma nova cena para começar."
        />
      </section>
    );
  }

  if (sceneQuery.isLoading) {
    return (
      <section className="p-6">
        <LoadingState label="Carregando cena..." />
      </section>
    );
  }

  if (sceneQuery.isError) {
    return (
      <section className="p-6">
        <ErrorState message={sceneQuery.error.message} />
      </section>
    );
  }

  const scene = sceneQuery.data;

  if (!scene) {
    return null;
  }

  return (
    <section className="h-full overflow-y-auto p-4 md:p-6">
      <div className="grid min-h-full grid-rows-[auto_minmax(0,1fr)] gap-4">
        <form onSubmit={handleMetadataSubmit} className="grid gap-3 rounded-lg border border-zinc-200 bg-white p-4">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <p className="text-xs font-medium uppercase text-zinc-500">Cena</p>
              <p className="mt-1 text-sm text-zinc-600">{scene.wordCount} palavras</p>
            </div>
            <Button type="submit" disabled={metadataMutation.isPending || !title.trim()}>
              {metadataMutation.isPending ? "Salvando..." : "Salvar dados"}
            </Button>
          </div>

          <div className="grid gap-3 md:grid-cols-[1fr_180px]">
            <TextField label="Título" value={title} onChange={(event) => setTitle(event.target.value)} />
            <label className="grid gap-1 text-sm">
              <span className="font-medium text-zinc-700">Status</span>
              <select
                value={status}
                onChange={(event) => setStatus(event.target.value as SceneStatus)}
                className="min-h-10 rounded-md border border-zinc-300 bg-white px-3 py-2 outline-none focus:border-zinc-700"
              >
                {SCENE_STATUSES.map((sceneStatus) => (
                  <option key={sceneStatus} value={sceneStatus}>
                    {sceneStatus}
                  </option>
                ))}
              </select>
            </label>
          </div>

          <TextAreaField label="Resumo" value={summary} rows={2} onChange={(event) => setSummary(event.target.value)} />

          {metadataMutation.isError ? (
            <FeedbackMessage variant="error">{metadataMutation.error.message}</FeedbackMessage>
          ) : null}
        </form>

        <Card className="grid min-h-0 gap-3 p-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h2 className="text-sm font-semibold text-zinc-950">Conteúdo textual</h2>
              <p className="text-xs text-zinc-500">Salvamento manual nesta etapa do MVP.</p>
            </div>
            <Button type="button" onClick={() => contentMutation.mutate()} disabled={contentMutation.isPending}>
              {contentMutation.isPending ? "Salvando..." : "Salvar conteúdo"}
            </Button>
          </div>

          <Textarea
            value={contentText}
            onChange={(event) => setContentText(event.target.value)}
            className="min-h-[360px] flex-1 resize-none py-3 text-base leading-7"
          />

          <div className="flex items-center justify-between gap-3 text-sm">
            <span className="text-zinc-600">Word count oficial: {scene.wordCount}</span>
            {contentMutation.isError ? <span className="text-red-700">{contentMutation.error.message}</span> : null}
            {contentMutation.isSuccess ? <span className="text-emerald-700">Conteúdo salvo.</span> : null}
          </div>
        </Card>
      </div>
    </section>
  );
}
