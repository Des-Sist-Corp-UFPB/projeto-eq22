import { useEffect, useState } from "react";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { getSceneVersion, listSceneVersions } from "@/features/scenes/api/scenes-api";
import type { SceneVersionSource } from "@/features/scenes/types";
import { queryKeys } from "@/lib/query/keys";

export type SceneVersionRestoreMode = "RESTORE" | "SAVE_AND_RESTORE" | "DISCARD_AND_RESTORE";

type SceneVersionHistoryPanelProps = {
  sceneId: string;
  hasUnsavedContent: boolean;
  contentSaveInFlight: boolean;
  restoreDisabled: boolean;
  restorePending: boolean;
  restoreError: string | null;
  onClose: () => void;
  onRestoreVersion: (versionId: string, mode: SceneVersionRestoreMode) => Promise<void>;
};

const sourceLabels: Record<SceneVersionSource, string> = {
  AUTO_SAVE: "Antes do salvamento automatico",
  MANUAL_SAVE: "Salvamento manual",
  RESTORE_SAFETY: "Antes da restauracao",
  DELETE_SAFETY: "Antes da exclusao",
};

export function SceneVersionHistoryPanel({
  sceneId,
  hasUnsavedContent,
  contentSaveInFlight,
  restoreDisabled,
  restorePending,
  restoreError,
  onClose,
  onRestoreVersion,
}: SceneVersionHistoryPanelProps) {
  const [selectedVersionId, setSelectedVersionId] = useState<string | null>(null);
  const [dirtyRestorePromptOpen, setDirtyRestorePromptOpen] = useState(false);
  const versionsQuery = useInfiniteQuery({
    queryKey: queryKeys.sceneVersions(sceneId),
    queryFn: ({ pageParam }) => listSceneVersions(sceneId, pageParam),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => (lastPage.hasNext ? lastPage.page + 1 : undefined),
  });
  const versions = versionsQuery.data?.pages.flatMap((page) => page.items) ?? [];
  const detailQuery = useQuery({
    queryKey: selectedVersionId ? queryKeys.sceneVersion(sceneId, selectedVersionId) : ["scenes", sceneId, "versions", "empty"],
    queryFn: () => getSceneVersion(sceneId, selectedVersionId as string),
    enabled: Boolean(selectedVersionId),
  });

  useEffect(() => {
    if (!selectedVersionId && versions[0]) {
      setSelectedVersionId(versions[0].id);
    }
  }, [selectedVersionId, versions]);

  function handleRestore() {
    if (!selectedVersionId || restoreDisabled) {
      return;
    }

    if (hasUnsavedContent) {
      setDirtyRestorePromptOpen(true);
      return;
    }

    const confirmed = window.confirm("Restaurar esta versao? O conteudo atual sera preservado no historico antes da troca.");
    if (confirmed) {
      void onRestoreVersion(selectedVersionId, "RESTORE");
    }
  }

  function handleDirtyRestore(mode: SceneVersionRestoreMode) {
    if (!selectedVersionId || restoreDisabled || restorePending) {
      return;
    }

    if (mode === "DISCARD_AND_RESTORE" && contentSaveInFlight) {
      return;
    }

    void onRestoreVersion(selectedVersionId, mode);
  }

  return (
    <div className="fixed inset-0 z-50 grid bg-zinc-950/40 p-4 md:place-items-center" role="dialog" aria-modal="true">
      <section className="grid max-h-full w-full max-w-5xl grid-rows-[auto_minmax(0,1fr)] overflow-hidden rounded-lg bg-white shadow-xl">
        <header className="flex items-center justify-between gap-3 border-b border-zinc-200 px-5 py-4">
          <div>
            <h2 className="text-base font-semibold text-zinc-950">Historico da cena</h2>
            <p className="text-xs text-zinc-500">Versoes recuperaveis do conteudo textual.</p>
          </div>
          <Button type="button" variant="ghost" size="sm" onClick={onClose}>
            Fechar
          </Button>
        </header>

        <div className="grid min-h-0 gap-4 overflow-y-auto p-5 md:grid-cols-[320px_minmax(0,1fr)]">
          <aside className="min-h-0 overflow-y-auto rounded-md border border-zinc-200">
            {versionsQuery.isLoading ? <LoadingState label="Carregando historico..." /> : null}
            {versionsQuery.isError ? <ErrorState message="Nao foi possivel carregar o historico." /> : null}
            {versions.length === 0 && versionsQuery.isSuccess ? (
              <p className="p-4 text-sm text-zinc-500">Nenhuma versao salva ainda.</p>
            ) : null}
            {versions.map((version) => (
              <button
                key={version.id}
                type="button"
                onClick={() => {
                  setSelectedVersionId(version.id);
                  setDirtyRestorePromptOpen(false);
                }}
                className={`block w-full border-b border-zinc-100 p-3 text-left transition last:border-b-0 ${
                  selectedVersionId === version.id ? "bg-zinc-100" : "hover:bg-zinc-50"
                }`}
              >
                <p className="text-xs font-medium text-zinc-500">{sourceLabels[version.source]}</p>
                <p className="mt-1 text-sm font-semibold text-zinc-950">{new Date(version.createdAt).toLocaleString()}</p>
                <p className="mt-1 text-xs text-zinc-500">{version.wordCount} palavras</p>
                <p className="mt-2 max-h-16 overflow-hidden text-xs leading-5 text-zinc-600">
                  {version.contentTextPreview || "Sem texto de previa."}
                </p>
              </button>
            ))}
            {versionsQuery.hasNextPage ? (
              <div className="border-t border-zinc-100 p-3">
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  className="w-full"
                  onClick={() => void versionsQuery.fetchNextPage()}
                  disabled={versionsQuery.isFetchingNextPage}
                >
                  {versionsQuery.isFetchingNextPage ? "Carregando..." : "Carregar versoes anteriores"}
                </Button>
              </div>
            ) : null}
          </aside>

          <section className="grid min-h-0 grid-rows-[auto_minmax(0,1fr)_auto] gap-3">
            <div>
              <h3 className="text-sm font-semibold text-zinc-950">Previa</h3>
              <p className="text-xs text-zinc-500">A restauracao substitui o conteudo atual da cena.</p>
            </div>
            <div className="min-h-[260px] overflow-y-auto whitespace-pre-wrap rounded-md border border-zinc-200 bg-zinc-50 p-4 text-sm leading-7 text-zinc-800">
              {detailQuery.isLoading ? "Carregando previa..." : null}
              {detailQuery.isError ? "Nao foi possivel carregar esta versao." : null}
              {detailQuery.data ? detailQuery.data.contentText || "Esta versao nao tem texto plain text." : null}
            </div>
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="min-h-6">
                {restoreError ? <FeedbackMessage variant="error">{restoreError}</FeedbackMessage> : null}
              </div>
              <Button
                type="button"
                onClick={handleRestore}
                disabled={!selectedVersionId || restoreDisabled || restorePending}
              >
                {restorePending ? "Restaurando..." : "Restaurar versao"}
              </Button>
            </div>
            {dirtyRestorePromptOpen ? (
              <div className="rounded-md border border-amber-200 bg-amber-50 p-3">
                <p className="text-sm font-semibold text-amber-950">Existem alteracoes locais nao salvas.</p>
                <p className="mt-1 text-xs leading-5 text-amber-900">
                  Escolha como tratar o conteudo atual antes de restaurar a versao selecionada.
                </p>
                <div className="mt-3 flex flex-wrap gap-2">
                  <Button type="button" size="sm" onClick={() => handleDirtyRestore("SAVE_AND_RESTORE")} disabled={restorePending}>
                    Salvar alterações e restaurar
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    variant="secondary"
                    className="border-red-300 text-red-700 hover:bg-red-50"
                    onClick={() => handleDirtyRestore("DISCARD_AND_RESTORE")}
                    disabled={restorePending || contentSaveInFlight}
                    title={
                      contentSaveInFlight
                        ? "Ha um salvamento em andamento. Aguarde a conclusao antes de descartar alteracoes locais."
                        : undefined
                    }
                  >
                    Descartar alterações locais e restaurar
                  </Button>
                  {contentSaveInFlight ? (
                    <p className="basis-full text-xs leading-5 text-amber-900">
                      Ha um salvamento em andamento. Aguarde a conclusao antes de descartar alteracoes locais.
                    </p>
                  ) : null}
                  <Button
                    type="button"
                    size="sm"
                    variant="ghost"
                    onClick={() => setDirtyRestorePromptOpen(false)}
                    disabled={restorePending}
                  >
                    Cancelar
                  </Button>
                </div>
              </div>
            ) : null}
          </section>
        </div>
      </section>
    </div>
  );
}
