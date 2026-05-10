"use client";

import { type FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Card } from "@/components/ui/card";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { deleteScene, getScene, updateScene, updateSceneContent } from "@/features/scenes/api/scenes-api";
import { SceneContentEditor } from "@/features/scenes/components/scene-content-editor";
import { SceneEditorHeader, type ContentSaveStatus } from "@/features/scenes/components/scene-editor-header";
import { SceneEmptyState } from "@/features/scenes/components/scene-empty-state";
import { SceneMetadataForm } from "@/features/scenes/components/scene-metadata-form";
import { ScenePlanningPanel } from "@/features/scenes/components/scene-planning-panel";
import type { SceneStatus } from "@/features/scenes/types";
import { queryKeys } from "@/lib/query/keys";

const SCENE_STATUSES: SceneStatus[] = ["IDEA", "PLANNED", "DRAFT", "WRITTEN", "REVISED", "FINAL"];
const METADATA_FORM_ID = "scene-metadata-form";
const CONTENT_AUTOSAVE_DELAY_MS = 1200;

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
  const loadedSceneIdRef = useRef<string | null>(null);
  const autosaveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const contentSavePendingRef = useRef(false);
  const currentContentJsonRef = useRef("");
  const currentContentTextRef = useRef("");
  const lastSavedContentJsonRef = useRef("");
  const lastSavedContentTextRef = useRef("");
  const [title, setTitle] = useState("");
  const [summary, setSummary] = useState("");
  const [status, setStatus] = useState<SceneStatus>("IDEA");
  const [contentJson, setContentJson] = useState("");
  const [contentText, setContentText] = useState("");
  const [lastSavedContentJson, setLastSavedContentJson] = useState("");
  const [lastSavedContentText, setLastSavedContentText] = useState("");
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

  const clearPendingAutosave = useCallback(() => {
    if (autosaveTimerRef.current) {
      clearTimeout(autosaveTimerRef.current);
      autosaveTimerRef.current = null;
    }
  }, []);

  useEffect(() => {
    contentSavePendingRef.current = contentMutation.isPending;
  }, [contentMutation.isPending]);

  useEffect(() => clearPendingAutosave, [clearPendingAutosave]);

  useEffect(() => {
    clearPendingAutosave();
    activeSceneIdRef.current = sceneId;
    loadedSceneIdRef.current = null;
    currentContentJsonRef.current = "";
    currentContentTextRef.current = "";
    lastSavedContentJsonRef.current = "";
    lastSavedContentTextRef.current = "";
    metadataMutation.reset();
    contentMutation.reset();
    setTitle("");
    setSummary("");
    setStatus("IDEA");
    setContentJson("");
    setContentText("");
    setLastSavedContentJson("");
    setLastSavedContentText("");
    setLoadedSceneId(null);
  }, [clearPendingAutosave, sceneId]);

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
    setLastSavedContentJson(queriedScene.contentJson ?? "");
    setLastSavedContentText(queriedScene.contentText ?? "");
    currentContentJsonRef.current = queriedScene.contentJson ?? "";
    currentContentTextRef.current = queriedScene.contentText ?? "";
    lastSavedContentJsonRef.current = queriedScene.contentJson ?? "";
    lastSavedContentTextRef.current = queriedScene.contentText ?? "";
    loadedSceneIdRef.current = queriedScene.id;
    setLoadedSceneId(queriedScene.id);
  }, [sceneId, sceneQuery.data]);

  function handleMetadataSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!sceneId || !title.trim()) {
      return;
    }

    metadataMutation.mutate();
  }

  async function saveSceneContent(targetSceneId: string, nextContentJson: string, nextContentText: string) {
    if (!targetSceneId || targetSceneId !== activeSceneIdRef.current) {
      return;
    }

    try {
      const savedScene = await contentMutation.mutateAsync({
        targetSceneId,
        contentJson: nextContentJson,
        contentText: nextContentText,
      });

      void queryClient.setQueryData(queryKeys.scene(savedScene.id), savedScene);
      void queryClient.invalidateQueries({ queryKey: queryKeys.outline(bookId) });

      if (activeSceneIdRef.current !== targetSceneId || savedScene.id !== targetSceneId) {
        return;
      }

      const savedContentJson = savedScene.contentJson ?? "";
      const savedContentText = savedScene.contentText ?? "";
      const currentContentMatchesSavedRequest =
        currentContentJsonRef.current === nextContentJson && currentContentTextRef.current === nextContentText;

      if (currentContentMatchesSavedRequest) {
        currentContentJsonRef.current = savedContentJson;
        currentContentTextRef.current = savedContentText;
        setContentJson(savedContentJson);
        setContentText(savedContentText);
      }

      lastSavedContentJsonRef.current = savedContentJson;
      lastSavedContentTextRef.current = savedContentText;
      setLastSavedContentJson(savedContentJson);
      setLastSavedContentText(savedContentText);
    } catch {
      // A mutation mantém o estado de erro para a UI; o conteúdo local fica preservado.
    }
  }

  function handleSaveContent(targetSceneId: string) {
    clearPendingAutosave();
    void saveSceneContent(targetSceneId, currentContentJsonRef.current, currentContentTextRef.current);
  }

  function scheduleAutosave(targetSceneId: string, nextContentJson: string, nextContentText: string) {
    if (!targetSceneId || targetSceneId !== activeSceneIdRef.current || loadedSceneIdRef.current !== targetSceneId) {
      return;
    }

    clearPendingAutosave();

    if (nextContentJson === lastSavedContentJsonRef.current && nextContentText === lastSavedContentTextRef.current) {
      return;
    }

    autosaveTimerRef.current = setTimeout(() => {
      autosaveTimerRef.current = null;

      if (targetSceneId !== activeSceneIdRef.current || loadedSceneIdRef.current !== targetSceneId) {
        return;
      }

      if (nextContentJson === lastSavedContentJsonRef.current && nextContentText === lastSavedContentTextRef.current) {
        return;
      }

      if (contentSavePendingRef.current) {
        scheduleAutosave(targetSceneId, nextContentJson, nextContentText);
        return;
      }

      void saveSceneContent(targetSceneId, nextContentJson, nextContentText);
    }, CONTENT_AUTOSAVE_DELAY_MS);
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

  const activeContentMutationSceneId = contentMutation.variables?.targetSceneId;
  const hasUnsavedContent = contentJson !== lastSavedContentJson || contentText !== lastSavedContentText;
  const contentSaveStatus: ContentSaveStatus = contentMutation.isPending && activeContentMutationSceneId === scene.id
    ? "saving"
    : contentMutation.isError && activeContentMutationSceneId === scene.id
      ? "error"
      : hasUnsavedContent
        ? "editing"
        : "saved";

  return (
    <section className="h-full overflow-y-auto bg-zinc-100/70 p-4 md:p-6 lg:p-8">
      <Card className="mx-auto grid min-h-full max-w-7xl grid-rows-[auto_auto_auto_minmax(0,1fr)] overflow-hidden border-zinc-200 bg-white shadow-xl shadow-zinc-200/70">
        <SceneEditorHeader
          scene={scene}
          metadataFormId={METADATA_FORM_ID}
          title={title}
          contentSaveStatus={contentSaveStatus}
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

        <ScenePlanningPanel bookId={bookId} scene={scene} />

        <SceneContentEditor
          editorKey={scene.id}
          contentJson={contentJson}
          contentText={contentText}
          wordCount={scene.wordCount}
          isSuccess={contentMutation.isSuccess}
          isError={contentMutation.isError}
          saveStatus={contentSaveStatus}
          onContentChange={(sourceSceneId, nextContentJson, nextContentText) => {
            if (sourceSceneId !== activeSceneIdRef.current) {
              return;
            }

            if (!contentMutation.isPending) {
              contentMutation.reset();
            }
            currentContentJsonRef.current = nextContentJson;
            currentContentTextRef.current = nextContentText;
            setContentJson(nextContentJson);
            setContentText(nextContentText);
            scheduleAutosave(sourceSceneId, nextContentJson, nextContentText);
          }}
        />
      </Card>
    </section>
  );
}
