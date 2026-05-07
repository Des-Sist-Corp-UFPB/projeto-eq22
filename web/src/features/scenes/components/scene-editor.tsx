"use client";

import { type FormEvent, useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Card } from "@/components/ui/card";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { deleteScene, getScene, updateScene, updateSceneContent } from "@/features/scenes/api/scenes-api";
import { SceneContentEditor } from "@/features/scenes/components/scene-content-editor";
import { SceneEditorHeader } from "@/features/scenes/components/scene-editor-header";
import { SceneEmptyState } from "@/features/scenes/components/scene-empty-state";
import { SceneMetadataForm } from "@/features/scenes/components/scene-metadata-form";
import type { SceneStatus } from "@/features/scenes/types";
import { queryKeys } from "@/lib/query/keys";

const SCENE_STATUSES: SceneStatus[] = ["IDEA", "PLANNED", "DRAFT", "WRITTEN", "REVISED", "FINAL"];
const METADATA_FORM_ID = "scene-metadata-form";

type SceneEditorProps = {
  bookId: string;
  sceneId: string | null;
  onSceneDeleted: () => void;
};

type SaveContentVariables = {
  targetSceneId: string;
  contentJson: string;
  contentText: string;
};

export function SceneEditor({ bookId, sceneId, onSceneDeleted }: SceneEditorProps) {
  const queryClient = useQueryClient();
  const activeSceneIdRef = useRef<string | null>(sceneId);
  const [title, setTitle] = useState("");
  const [summary, setSummary] = useState("");
  const [status, setStatus] = useState<SceneStatus>("IDEA");
  const [contentJson, setContentJson] = useState("");
  const [contentText, setContentText] = useState("");
  const [loadedSceneId, setLoadedSceneId] = useState<string | null>(null);

  const sceneQuery = useQuery({
    queryKey: sceneId ? queryKeys.scene(sceneId) : ["scenes", "empty"],
    queryFn: () => getScene(sceneId as string),
    enabled: Boolean(sceneId),
  });

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
    mutationFn: ({ targetSceneId, contentJson, contentText }: SaveContentVariables) =>
      updateSceneContent(targetSceneId, {
        contentText,
        contentJson,
      }),
    onSuccess: (scene) => {
      void queryClient.setQueryData(queryKeys.scene(scene.id), scene);
      void queryClient.invalidateQueries({ queryKey: queryKeys.outline(bookId) });

      if (activeSceneIdRef.current === scene.id) {
        setContentJson(scene.contentJson ?? "");
        setContentText(scene.contentText ?? "");
      }
    },
  });

  const deleteMutation = useMutation({
    mutationFn: () => deleteScene(sceneId as string),
    onSuccess: () => {
      if (sceneId) {
        queryClient.removeQueries({ queryKey: queryKeys.scene(sceneId) });
      }
      void queryClient.invalidateQueries({ queryKey: queryKeys.outline(bookId) });
      onSceneDeleted();
    },
  });

  useEffect(() => {
    activeSceneIdRef.current = sceneId;
    metadataMutation.reset();
    contentMutation.reset();
    setTitle("");
    setSummary("");
    setStatus("IDEA");
    setContentJson("");
    setContentText("");
    setLoadedSceneId(null);
  }, [sceneId]);

  useEffect(() => {
    const queriedScene = sceneQuery.data;
    if (!queriedScene || queriedScene.id !== sceneId) {
      return;
    }

    setTitle(queriedScene.title);
    setSummary(queriedScene.summary ?? "");
    setStatus(queriedScene.status);
    setContentJson(queriedScene.contentJson ?? "");
    setContentText(queriedScene.contentText ?? "");
    setLoadedSceneId(queriedScene.id);
  }, [sceneId, sceneQuery.data]);

  function handleMetadataSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!sceneId || !title.trim()) {
      return;
    }

    metadataMutation.mutate();
  }

  function handleSaveContent(targetSceneId: string) {
    if (!targetSceneId || targetSceneId !== activeSceneIdRef.current) {
      return;
    }

    contentMutation.mutate({
      targetSceneId,
      contentJson,
      contentText,
    });
  }

  function handleDeleteScene(sceneTitle: string) {
    if (!sceneId) {
      return;
    }

    const confirmed = window.confirm(`Excluir a cena "${sceneTitle}"? Esta ação não pode ser desfeita nesta etapa.`);
    if (confirmed) {
      deleteMutation.mutate();
    }
  }

  if (!sceneId) {
    return (
      <SceneEmptyState
        title="Selecione uma cena"
        description="Selecione uma cena no esboço ou crie uma nova cena para começar a escrever."
      />
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
        <ErrorState message="Não foi possível carregar a cena. Verifique se o backend está rodando e tente novamente." />
      </section>
    );
  }

  const scene = sceneQuery.data?.id === sceneId ? sceneQuery.data : null;

  if (!scene) {
    return (
      <section className="p-6">
        <LoadingState label="Carregando cena..." />
      </section>
    );
  }

  if (loadedSceneId !== scene.id) {
    return (
      <section className="p-6">
        <LoadingState label="Preparando editor..." />
      </section>
    );
  }

  return (
    <section className="h-full overflow-y-auto bg-zinc-50 p-4 md:p-6">
      <Card className="mx-auto grid min-h-full max-w-6xl grid-rows-[auto_auto_minmax(0,1fr)] overflow-hidden">
        <SceneEditorHeader
          scene={scene}
          metadataFormId={METADATA_FORM_ID}
          title={title}
          metadataPending={metadataMutation.isPending}
          contentPending={contentMutation.isPending}
          deletePending={deleteMutation.isPending}
          deleteError={deleteMutation.isError}
          onSaveContent={() => handleSaveContent(scene.id)}
          onDeleteScene={handleDeleteScene}
        />

        <SceneMetadataForm
          formId={METADATA_FORM_ID}
          title={title}
          summary={summary}
          status={status}
          statuses={SCENE_STATUSES}
          isSuccess={metadataMutation.isSuccess}
          isError={metadataMutation.isError}
          onSubmit={handleMetadataSubmit}
          onTitleChange={(nextTitle) => {
            metadataMutation.reset();
            setTitle(nextTitle);
          }}
          onSummaryChange={(nextSummary) => {
            metadataMutation.reset();
            setSummary(nextSummary);
          }}
          onStatusChange={(nextStatus) => {
            metadataMutation.reset();
            setStatus(nextStatus);
          }}
        />

        <SceneContentEditor
          editorKey={scene.id}
          contentJson={contentJson}
          contentText={contentText}
          wordCount={scene.wordCount}
          isSuccess={contentMutation.isSuccess}
          isError={contentMutation.isError}
          onContentChange={(sourceSceneId, nextContentJson, nextContentText) => {
            if (sourceSceneId !== activeSceneIdRef.current) {
              return;
            }

            contentMutation.reset();
            setContentJson(nextContentJson);
            setContentText(nextContentText);
          }}
        />
      </Card>
    </section>
  );
}
