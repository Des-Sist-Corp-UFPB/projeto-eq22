"use client";

import { FormEvent, useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { Textarea } from "@/components/ui/textarea";
import { getScene, updateScene, updateSceneContent } from "@/features/scenes/api/scenes-api";
import type { SceneStatus } from "@/features/scenes/types";
import { queryKeys } from "@/lib/query/keys";

const SCENE_STATUSES: SceneStatus[] = ["IDEA", "PLANNED", "DRAFT", "WRITTEN", "REVISED", "FINAL"];
const METADATA_FORM_ID = "scene-metadata-form";

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
          description="Selecione uma cena no esboço ou crie uma nova cena para começar a escrever."
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
    <section className="h-full overflow-y-auto bg-zinc-50 p-4 md:p-6">
      <Card className="mx-auto grid min-h-full max-w-6xl grid-rows-[auto_auto_minmax(0,1fr)] overflow-hidden">
        <header className="border-b border-zinc-200 bg-white px-4 py-4 md:px-6">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div className="min-w-0">
              <p className="text-xs font-medium uppercase text-zinc-500">Cena</p>
              <h1 className="mt-1 truncate text-xl font-semibold text-zinc-950 md:text-2xl">{scene.title}</h1>
              <div className="mt-3 flex flex-wrap items-center gap-2">
                <Badge>{scene.status}</Badge>
                <Badge variant="outline">{scene.wordCount} palavras</Badge>
              </div>
            </div>

            <div className="flex shrink-0 flex-wrap gap-2">
              <Button
                type="submit"
                form={METADATA_FORM_ID}
                variant="secondary"
                disabled={metadataMutation.isPending || !title.trim()}
              >
                {metadataMutation.isPending ? "Salvando..." : "Salvar dados"}
              </Button>
              <Button type="button" onClick={() => contentMutation.mutate()} disabled={contentMutation.isPending}>
                {contentMutation.isPending ? "Salvando..." : "Salvar conteúdo"}
              </Button>
            </div>
          </div>
        </header>

        <form
          id={METADATA_FORM_ID}
          onSubmit={handleMetadataSubmit}
          className="grid gap-4 border-b border-zinc-200 bg-zinc-50/80 px-4 py-4 md:grid-cols-[minmax(0,1fr)_180px] md:px-6"
        >
          <label className="grid gap-1 text-sm">
            <span className="font-medium text-zinc-700">Título</span>
            <input
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              className="min-h-10 rounded-md border border-zinc-300 bg-white px-3 py-2 outline-none transition focus:border-zinc-800 focus:ring-2 focus:ring-zinc-200"
            />
          </label>

          <label className="grid gap-1 text-sm">
            <span className="font-medium text-zinc-700">Status</span>
            <select
              value={status}
              onChange={(event) => setStatus(event.target.value as SceneStatus)}
              className="min-h-10 rounded-md border border-zinc-300 bg-white px-3 py-2 outline-none transition focus:border-zinc-800 focus:ring-2 focus:ring-zinc-200"
            >
              {SCENE_STATUSES.map((sceneStatus) => (
                <option key={sceneStatus} value={sceneStatus}>
                  {sceneStatus}
                </option>
              ))}
            </select>
          </label>

          <label className="grid gap-1 text-sm md:col-span-2">
            <span className="font-medium text-zinc-700">Resumo</span>
            <Textarea
              value={summary}
              rows={3}
              onChange={(event) => setSummary(event.target.value)}
              className="resize-y focus:ring-2 focus:ring-zinc-200"
              placeholder="Resumo breve da função dramática desta cena."
            />
          </label>

          {metadataMutation.isError ? (
            <FeedbackMessage variant="error" className="md:col-span-2">
              Não foi possível salvar os dados da cena. Verifique o backend e tente novamente.
            </FeedbackMessage>
          ) : null}
        </form>

        <div className="grid min-h-0 gap-4 bg-white px-4 py-5 md:px-6">
          <div className="mx-auto grid w-full max-w-4xl gap-3">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <h2 className="text-sm font-semibold text-zinc-950">Conteúdo textual</h2>
                <p className="text-xs text-zinc-500">
                  Salvamento manual. O contador é atualizado com o retorno do backend.
                </p>
              </div>
              <span className="text-sm text-zinc-600">Word count oficial: {scene.wordCount}</span>
            </div>

            <Textarea
              value={contentText}
              onChange={(event) => setContentText(event.target.value)}
              placeholder="Escreva a cena aqui..."
              className="min-h-[62vh] resize-y rounded-lg border-zinc-200 bg-[#fffefb] px-5 py-5 text-[17px] leading-8 text-zinc-900 shadow-inner shadow-zinc-100 focus:border-zinc-800 focus:ring-2 focus:ring-zinc-200 md:px-7 md:py-6"
            />

            <div className="min-h-10">
              {contentMutation.isSuccess ? (
                <FeedbackMessage variant="success">Conteúdo salvo. Word count atualizado pelo backend.</FeedbackMessage>
              ) : null}

              {contentMutation.isError ? (
                <FeedbackMessage variant="error">
                  Não foi possível salvar o conteúdo agora. Verifique se o backend está rodando e tente novamente.
                </FeedbackMessage>
              ) : null}
            </div>
          </div>
        </div>
      </Card>
    </section>
  );
}
