import type { FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { InlineCreateForm } from "@/features/outline/components/inline-create-form";
import { ChapterItem } from "@/features/outline/components/chapter-item";
import { Field, WordCount } from "@/features/outline/components/outline-sidebar-parts";
import type { OutlineChapter, OutlineSection, SectionType } from "@/features/outline/types";

type SectionItemProps = {
  section: OutlineSection;
  sectionTypes: SectionType[];
  selectedSceneId: string | null;
  editingSectionId: string | null;
  sectionTitle: string;
  sectionType: SectionType;
  editingChapterId: string | null;
  chapterTitle: string;
  chapterSummary: string;
  updateSectionPending: boolean;
  deleteSectionPending: boolean;
  createChapterPending: boolean;
  updateChapterPending: boolean;
  deleteChapterPending: boolean;
  createScenePending: boolean;
  deleteScenePending: boolean;
  reorderSectionPending: boolean;
  reorderChapterPending: boolean;
  reorderScenePending: boolean;
  canMoveUp: boolean;
  canMoveDown: boolean;
  onSectionTitleChange: (title: string) => void;
  onSectionTypeChange: (type: SectionType) => void;
  onStartEditSection: (section: OutlineSection) => void;
  onCancelEditSection: () => void;
  onSubmitSection: (event: FormEvent<HTMLFormElement>, sectionId: string) => void;
  onDeleteSection: (section: OutlineSection) => void;
  onMoveSectionUp: (sectionId: string) => void;
  onMoveSectionDown: (sectionId: string) => void;
  onCreateChapter: (sectionId: string, title: string) => void;
  onChapterTitleChange: (title: string) => void;
  onChapterSummaryChange: (summary: string) => void;
  onStartEditChapter: (chapter: OutlineChapter) => void;
  onCancelEditChapter: () => void;
  onSubmitChapter: (event: FormEvent<HTMLFormElement>, chapterId: string) => void;
  onDeleteChapter: (chapter: OutlineChapter) => void;
  onMoveChapterUp: (section: OutlineSection, chapterId: string) => void;
  onMoveChapterDown: (section: OutlineSection, chapterId: string) => void;
  onCreateScene: (chapterId: string, title: string) => void;
  onSelectScene: (sceneId: string) => void;
  onDeleteScene: (sceneId: string, sceneTitle: string) => void;
  onMoveSceneUp: (chapter: OutlineChapter, sceneId: string) => void;
  onMoveSceneDown: (chapter: OutlineChapter, sceneId: string) => void;
};

export function SectionItem({
  section,
  sectionTypes,
  selectedSceneId,
  editingSectionId,
  sectionTitle,
  sectionType,
  editingChapterId,
  chapterTitle,
  chapterSummary,
  updateSectionPending,
  deleteSectionPending,
  createChapterPending,
  updateChapterPending,
  deleteChapterPending,
  createScenePending,
  deleteScenePending,
  reorderSectionPending,
  reorderChapterPending,
  reorderScenePending,
  canMoveUp,
  canMoveDown,
  onSectionTitleChange,
  onSectionTypeChange,
  onStartEditSection,
  onCancelEditSection,
  onSubmitSection,
  onDeleteSection,
  onMoveSectionUp,
  onMoveSectionDown,
  onCreateChapter,
  onChapterTitleChange,
  onChapterSummaryChange,
  onStartEditChapter,
  onCancelEditChapter,
  onSubmitChapter,
  onDeleteChapter,
  onMoveChapterUp,
  onMoveChapterDown,
  onCreateScene,
  onSelectScene,
  onDeleteScene,
  onMoveSceneUp,
  onMoveSceneDown,
}: SectionItemProps) {
  return (
    <section className="group/section rounded-md border border-zinc-200 bg-white shadow-sm shadow-zinc-200/50">
      <div className="border-b border-zinc-100 bg-white px-3 py-3">
        {editingSectionId === section.id ? (
          <form onSubmit={(event) => onSubmitSection(event, section.id)} className="grid gap-2">
            <Field label="Nome da seção">
              <input
                value={sectionTitle}
                onChange={(event) => onSectionTitleChange(event.target.value)}
                className="min-h-8 rounded-md border border-zinc-300 bg-white px-2 py-1 text-sm outline-none focus:border-zinc-800"
              />
            </Field>
            <Field label="Tipo">
              <select
                value={sectionType}
                onChange={(event) => onSectionTypeChange(event.target.value as SectionType)}
                className="min-h-8 rounded-md border border-zinc-300 bg-white px-2 py-1 text-sm outline-none focus:border-zinc-800"
              >
                {sectionTypes.map((type) => (
                  <option key={type} value={type}>
                    {type}
                  </option>
                ))}
              </select>
            </Field>
            <div className="flex gap-2">
              <Button type="submit" size="sm" disabled={updateSectionPending || !sectionTitle.trim()}>
                Salvar
              </Button>
              <Button type="button" size="sm" variant="secondary" onClick={onCancelEditSection}>
                Cancelar
              </Button>
            </div>
          </form>
        ) : (
          <div className="grid gap-2">
            <div className="flex items-center justify-between gap-3">
              <div className="min-w-0">
                <p className="text-[11px] font-medium uppercase text-zinc-500">Seção · {section.type}</p>
                <h2 className="truncate text-sm font-semibold text-zinc-900">{section.title}</h2>
              </div>
              <WordCount count={section.wordCount} />
            </div>
            <div className="flex flex-wrap gap-1.5 opacity-70 transition group-hover/section:opacity-100 focus-within:opacity-100">
              <Button
                type="button"
                variant="ghost"
                size="sm"
                aria-label={`Mover seção ${section.title} para cima`}
                title="Mover para cima"
                disabled={!canMoveUp || reorderSectionPending}
                onClick={() => onMoveSectionUp(section.id)}
              >
                ↑
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                aria-label={`Mover seção ${section.title} para baixo`}
                title="Mover para baixo"
                disabled={!canMoveDown || reorderSectionPending}
                onClick={() => onMoveSectionDown(section.id)}
              >
                ↓
              </Button>
              <Button type="button" variant="ghost" size="sm" onClick={() => onStartEditSection(section)}>
                Editar
              </Button>
              <Button type="button" variant="ghost" size="sm" disabled={deleteSectionPending} onClick={() => onDeleteSection(section)}>
                Excluir
              </Button>
            </div>
          </div>
        )}
      </div>

      <div className="grid gap-3 bg-zinc-50/40 p-3">
        <InlineCreateForm
          compact
          ariaLabel={`Novo capítulo em ${section.title}`}
          placeholder="Novo capítulo"
          buttonLabel="Cap."
          disabled={createChapterPending}
          onCreate={(title) => onCreateChapter(section.id, title)}
        />

        {section.chapters.length === 0 ? (
          <EmptyState size="sm" title="Nenhum capítulo" description="Esta seção ainda não tem capítulos." />
        ) : (
          <div className="grid gap-4">
            {section.chapters.map((chapter, chapterIndex) => (
              <ChapterItem
                key={chapter.id}
                chapter={chapter}
                canMoveUp={chapterIndex > 0}
                canMoveDown={chapterIndex < section.chapters.length - 1}
                isEditing={editingChapterId === chapter.id}
                chapterTitle={chapterTitle}
                chapterSummary={chapterSummary}
                selectedSceneId={selectedSceneId}
                updatePending={updateChapterPending}
                deletePending={deleteChapterPending}
                createScenePending={createScenePending}
                deleteScenePending={deleteScenePending}
                reorderPending={reorderChapterPending}
                reorderScenePending={reorderScenePending}
                onTitleChange={onChapterTitleChange}
                onSummaryChange={onChapterSummaryChange}
                onStartEdit={onStartEditChapter}
                onCancelEdit={onCancelEditChapter}
                onSubmit={onSubmitChapter}
                onDeleteChapter={onDeleteChapter}
                onMoveChapterUp={(chapterId) => onMoveChapterUp(section, chapterId)}
                onMoveChapterDown={(chapterId) => onMoveChapterDown(section, chapterId)}
                onCreateScene={onCreateScene}
                onSelectScene={onSelectScene}
                onDeleteScene={onDeleteScene}
                onMoveSceneUp={onMoveSceneUp}
                onMoveSceneDown={onMoveSceneDown}
              />
            ))}
          </div>
        )}
      </div>
    </section>
  );
}
