import type { FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { InlineCreateForm } from "@/features/outline/components/inline-create-form";
import { Field, WordCount } from "@/features/outline/components/outline-sidebar-parts";
import { SceneRow } from "@/features/outline/components/scene-row";
import type { OutlineChapter } from "@/features/outline/types";

type ChapterItemProps = {
  chapter: OutlineChapter;
  canMoveUp: boolean;
  canMoveDown: boolean;
  isEditing: boolean;
  chapterTitle: string;
  chapterSummary: string;
  selectedSceneId: string | null;
  updatePending: boolean;
  deletePending: boolean;
  createScenePending: boolean;
  deleteScenePending: boolean;
  reorderPending: boolean;
  reorderScenePending: boolean;
  onTitleChange: (title: string) => void;
  onSummaryChange: (summary: string) => void;
  onStartEdit: (chapter: OutlineChapter) => void;
  onCancelEdit: () => void;
  onSubmit: (event: FormEvent<HTMLFormElement>, chapterId: string) => void;
  onDeleteChapter: (chapter: OutlineChapter) => void;
  onMoveChapterUp: (chapterId: string) => void;
  onMoveChapterDown: (chapterId: string) => void;
  onCreateScene: (chapterId: string, title: string) => void;
  onSelectScene: (sceneId: string) => void;
  onDeleteScene: (sceneId: string, sceneTitle: string) => void;
  onMoveSceneUp: (chapter: OutlineChapter, sceneId: string) => void;
  onMoveSceneDown: (chapter: OutlineChapter, sceneId: string) => void;
};

export function ChapterItem({
  chapter,
  canMoveUp,
  canMoveDown,
  isEditing,
  chapterTitle,
  chapterSummary,
  selectedSceneId,
  updatePending,
  deletePending,
  createScenePending,
  deleteScenePending,
  reorderPending,
  reorderScenePending,
  onTitleChange,
  onSummaryChange,
  onStartEdit,
  onCancelEdit,
  onSubmit,
  onDeleteChapter,
  onMoveChapterUp,
  onMoveChapterDown,
  onCreateScene,
  onSelectScene,
  onDeleteScene,
  onMoveSceneUp,
  onMoveSceneDown,
}: ChapterItemProps) {
  return (
    <article className="grid gap-2 border-l-2 border-zinc-300 pl-3">
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
          <div className="flex flex-wrap gap-1.5">
            <Button
              type="button"
              variant="ghost"
              size="sm"
              aria-label={`Mover capítulo ${chapter.title} para cima`}
              title="Mover para cima"
              disabled={!canMoveUp || reorderPending}
              onClick={() => onMoveChapterUp(chapter.id)}
            >
              ↑
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              aria-label={`Mover capítulo ${chapter.title} para baixo`}
              title="Mover para baixo"
              disabled={!canMoveDown || reorderPending}
              onClick={() => onMoveChapterDown(chapter.id)}
            >
              ↓
            </Button>
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
        <div className="grid gap-1.5">
          {chapter.scenes.map((scene, sceneIndex) => (
            <SceneRow
              key={scene.id}
              scene={scene}
              isSelected={selectedSceneId === scene.id}
              deletePending={deleteScenePending}
              reorderPending={reorderScenePending}
              canMoveUp={sceneIndex > 0}
              canMoveDown={sceneIndex < chapter.scenes.length - 1}
              onSelect={onSelectScene}
              onDelete={onDeleteScene}
              onMoveUp={(sceneId) => onMoveSceneUp(chapter, sceneId)}
              onMoveDown={(sceneId) => onMoveSceneDown(chapter, sceneId)}
            />
          ))}
        </div>
      )}
    </article>
  );
}
