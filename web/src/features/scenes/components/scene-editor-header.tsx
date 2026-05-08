import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import type { Scene } from "@/features/scenes/types";

export type ContentSaveStatus = "saved" | "editing" | "saving" | "error";

type SceneEditorHeaderProps = {
  scene: Scene;
  metadataFormId: string;
  title: string;
  metadataPending: boolean;
  contentPending: boolean;
  deletePending: boolean;
  deleteError: boolean;
  onSaveContent: () => void;
  onDeleteScene: (sceneTitle: string) => void;
};

export function SceneEditorHeader({
  scene,
  metadataFormId,
  title,
  metadataPending,
  contentPending,
  deletePending,
  deleteError,
  onSaveContent,
  onDeleteScene,
}: SceneEditorHeaderProps) {
  return (
    <header className="border-b border-zinc-200 bg-white px-4 py-5 md:px-7">
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
          <Button type="submit" form={metadataFormId} variant="secondary" disabled={metadataPending || !title.trim()}>
            {metadataPending ? "Salvando..." : "Salvar dados"}
          </Button>
          <Button type="button" onClick={onSaveContent} disabled={contentPending}>
            {contentPending ? "Salvando..." : "Salvar conteúdo"}
          </Button>
          <Button type="button" variant="ghost" onClick={() => onDeleteScene(scene.title)} disabled={deletePending}>
            {deletePending ? "Excluindo..." : "Excluir cena"}
          </Button>
        </div>
      </div>

      {deleteError ? (
        <FeedbackMessage variant="error" className="mt-4">
          Não foi possível excluir a cena. Verifique a API e tente novamente.
        </FeedbackMessage>
      ) : null}
    </header>
  );
}
