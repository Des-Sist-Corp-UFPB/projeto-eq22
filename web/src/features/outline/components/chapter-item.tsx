import type { FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { InlineCreateForm } from "@/features/outline/components/inline-create-form";
import { Field, WordCount } from "@/features/outline/components/outline-sidebar-parts";
import { SceneRow } from "@/features/outline/components/scene-row";
import type { OutlineChapter } from "@/features/outline/types";

type ChapterItemProps = {
  chapter: OutlineChapter;
  isEditing: boolean;
  chapterTitle: string;
  chapterSummary: string;
  selectedSceneId: string | null;
  updatePending: boolean;
  deletePending: boolean;
  createScenePending: boolean;
  deleteScenePending: boolean;
  onTitleChange: (title: string) => void;
  onSummaryChange: (summary: string) => void;
  onStartEdit: (chapter: OutlineChapter) => void;
  onCancelEdit: () => void;
  onSubmit: (event: FormEvent<HTMLFormElement>, chapterId: string) => void;
  onDeleteChapter: (chapter: OutlineChapter) => void;
  onCreateScene: (chapterId: string, title: string) => void;
  onSelectScene: (sceneId: string) => void;
  onDeleteScene: (sceneId: string, sceneTitle: string) => void;
};

export function ChapterItem({
  chapter,
  isEditing,
  chapterTitle,
  chapterSummary,
  selectedSceneId,
  updatePending,
  deletePending,
  createScenePending,
  deleteScenePending,
  onTitleChange,
  onSummaryChange,
  onStartEdit,
  onCancelEdit,
  onSubmit,
  onDeleteChapter,
  onCreateScene,
  onSelectScene,
  onDeleteScene,
}: ChapterItemProps) {
  return (
    <article className="grid gap-2 border-l-2 border-zinc-200 pl-3">
      {isEditing ? (
        <form onSubmit={(event) => onSubmit(event, chapter.id)} className="grid gap-2">
          <Field label="Título do capítulo">
            <input
              value={chapterTitle}
              onChange={(event) => onTitleChange(event.target.value)}
              className="min-h-8 rounded-md border border-zinc-300 bg-white px-2 py-1 text-sm outline-none focus:border-zinc-800"
            />
          </Field>
          <Field label="Resumo">
            <textarea
              value={chapterSummary}
              rows={2}
              onChange={(event) => onSummaryChange(event.target.value)}
              className="rounded-md border border-zinc-300 bg-white px-2 py-1 text-sm outline-none focus:border-zinc-800"
            />
          </Field>
          <div className="flex gap-2">
            <Button type="submit" size="sm" disabled={updatePending || !chapterTitle.trim()}>
              Salvar
            </Button>
            <Button type="button" size="sm" variant="secondary" onClick={onCancelEdit}>
              Cancelar
            </Button>
          </div>
        </form>
      ) : (
        <>
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <p className="text-[11px] font-medium uppercase text-zinc-500">Capítulo</p>
              <h3 className="truncate text-sm font-medium text-zinc-800">{chapter.title}</h3>
              {chapter.summary ? <p className="mt-1 line-clamp-2 text-xs leading-5 text-zinc-500">{chapter.summary}</p> : null}
            </div>
            <WordCount count={chapter.wordCount} />
          </div>
          <div className="flex gap-2">
            <Button type="button" variant="secondary" size="sm" onClick={() => onStartEdit(chapter)}>
              Editar
            </Button>
            <Button type="button" variant="ghost" size="sm" disabled={deletePending} onClick={() => onDeleteChapter(chapter)}>
              Excluir
            </Button>
          </div>
        </>
      )}

      <InlineCreateForm
        compact
        ariaLabel={`Nova cena em ${chapter.title}`}
        placeholder="Nova cena"
        buttonLabel="Cena"
        disabled={createScenePending}
        onCreate={(title) => onCreateScene(chapter.id, title)}
      />

      {chapter.scenes.length === 0 ? (
        <EmptyState size="sm" title="Nenhuma cena" description="Este capítulo ainda não tem cenas." />
      ) : (
        <div className="grid gap-1">
          {chapter.scenes.map((scene) => (
            <SceneRow
              key={scene.id}
              scene={scene}
              isSelected={selectedSceneId === scene.id}
              deletePending={deleteScenePending}
              onSelect={onSelectScene}
              onDelete={onDeleteScene}
            />
          ))}
        </div>
      )}
    </article>
  );
}
