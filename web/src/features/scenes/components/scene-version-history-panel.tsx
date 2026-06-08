import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { getSceneVersion, listSceneVersions, restoreSceneVersion } from "@/features/scenes/api/scenes-api";
import type { Scene, SceneVersionSource } from "@/features/scenes/types";
import { queryKeys } from "@/lib/query/keys";

type SceneVersionHistoryPanelProps = {
  sceneId: string;
  expectedContentRevision: number;
  restoreDisabled: boolean;
  onClose: () => void;
  onRestored: (scene: Scene) => void;
};

const sourceLabels: Record<SceneVersionSource, string> = {
  AUTO_SAVE: "Antes do salvamento automático",
  MANUAL_SAVE: "Antes do salvamento manual",
  RESTORE_SAFETY: "Antes da restauração",
  DELETE_SAFETY: "Antes da exclusão",
};

export function SceneVersionHistoryPanel({
  sceneId,
  expectedContentRevision,
  restoreDisabled,
  onClose,
  onRestored,
}: SceneVersionHistoryPanelProps) {
  const queryClient = useQueryClient();
  const [selectedVersionId, setSelectedVersionId] = useState<string | null>(null);
  const versionsQuery = useQuery({
    queryKey: queryKeys.sceneVersions(sceneId),
    queryFn: () => listSceneVersions(sceneId),
  });
  const detailQuery = useQuery({
    queryKey: selectedVersionId ? queryKeys.sceneVersion(sceneId, selectedVersionId) : ["scenes", sceneId, "versions", "empty"],
    queryFn: () => getSceneVersion(sceneId, selectedVersionId as string),
    enabled: Boolean(selectedVersionId),
  });
  const restoreMutation = useMutation({
    mutationFn: (versionId: string) => restoreSceneVersion(sceneId, versionId, expectedContentRevision),
    onSuccess: (scene) => {
      queryClient.setQueryData(queryKeys.scene(scene.id), scene);
      void queryClient.invalidateQueries({ queryKey: queryKeys.sceneVersions(scene.id) });
      onRestored(scene);
    },
  });

  useEffect(() => {
    if (!selectedVersionId && versionsQuery.data?.items[0]) {
      setSelectedVersionId(versionsQuery.data.items[0].id);
    }
  }, [selectedVersionId, versionsQuery.data]);

  function handleRestore() {
    if (!selectedVersionId || restoreDisabled) {
      return;
    }

    const confirmed = window.confirm("Restaurar esta versão? O conteúdo atual será preservado no histórico antes da troca.");
    if (confirmed) {
      restoreMutation.mutate(selectedVersionId);
    }
  }

  return (
    <div className="fixed inset-0 z-50 grid bg-zinc-950/40 p-4 md:place-items-center" role="dialog" aria-modal="true">
      <section className="grid max-h-full w-full max-w-5xl grid-rows-[auto_minmax(0,1fr)] overflow-hidden rounded-lg bg-white shadow-xl">
        <header className="flex items-center justify-between gap-3 border-b border-zinc-200 px-5 py-4">
          <div>
            <h2 className="text-base font-semibold text-zinc-950">Histórico da cena</h2>
            <p className="text-xs text-zinc-500">Versões recuperáveis do conteúdo textual.</p>
          </div>
          <Button type="button" variant="ghost" size="sm" onClick={onClose}>
            Fechar
          </Button>
        </header>

        <div className="grid min-h-0 gap-4 overflow-y-auto p-5 md:grid-cols-[320px_minmax(0,1fr)]">
          <aside className="min-h-0 overflow-y-auto rounded-md border border-zinc-200">
            {versionsQuery.isLoading ? <LoadingState label="Carregando histórico..." /> : null}
            {versionsQuery.isError ? <ErrorState message="Não foi possível carregar o histórico." /> : null}
            {versionsQuery.data?.items.length === 0 ? (
              <p className="p-4 text-sm text-zinc-500">Nenhuma versão salva ainda.</p>
            ) : null}
            {versionsQuery.data?.items.map((version) => (
              <button
                key={version.id}
                type="button"
                onClick={() => setSelectedVersionId(version.id)}
                className={`block w-full border-b border-zinc-100 p-3 text-left transition last:border-b-0 ${
                  selectedVersionId === version.id ? "bg-zinc-100" : "hover:bg-zinc-50"
                }`}
              >
                <p className="text-xs font-medium text-zinc-500">{sourceLabels[version.source]}</p>
                <p className="mt-1 text-sm font-semibold text-zinc-950">{new Date(version.createdAt).toLocaleString()}</p>
                <p className="mt-1 text-xs text-zinc-500">{version.wordCount} palavras</p>
                <p className="mt-2 max-h-16 overflow-hidden text-xs leading-5 text-zinc-600">
                  {version.contentTextPreview || "Sem texto de prévia."}
                </p>
              </button>
            ))}
          </aside>

          <section className="grid min-h-0 grid-rows-[auto_minmax(0,1fr)_auto] gap-3">
            <div>
              <h3 className="text-sm font-semibold text-zinc-950">Prévia</h3>
              <p className="text-xs text-zinc-500">A restauração substitui o conteúdo atual da cena.</p>
            </div>
            <div className="min-h-[260px] overflow-y-auto whitespace-pre-wrap rounded-md border border-zinc-200 bg-zinc-50 p-4 text-sm leading-7 text-zinc-800">
              {detailQuery.isLoading ? "Carregando prévia..." : null}
              {detailQuery.isError ? "Não foi possível carregar esta versão." : null}
              {detailQuery.data ? detailQuery.data.contentText || "Esta versão não tem texto plain text." : null}
            </div>
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="min-h-6">
                {restoreMutation.isError ? (
                  <FeedbackMessage variant="error">Não foi possível restaurar. Recarregue a cena e tente novamente.</FeedbackMessage>
                ) : null}
              </div>
              <Button
                type="button"
                onClick={handleRestore}
                disabled={!selectedVersionId || restoreDisabled || restoreMutation.isPending}
              >
                {restoreMutation.isPending ? "Restaurando..." : "Restaurar versão"}
              </Button>
            </div>
          </section>
        </div>
      </section>
    </div>
  );
}
