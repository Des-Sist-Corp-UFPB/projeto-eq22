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
  onSectionTitleChange: (title: string) => void;
  onSectionTypeChange: (type: SectionType) => void;
  onStartEditSection: (section: OutlineSection) => void;
  onCancelEditSection: () => void;
  onSubmitSection: (event: FormEvent<HTMLFormElement>, sectionId: string) => void;
  onDeleteSection: (section: OutlineSection) => void;
  onCreateChapter: (sectionId: string, title: string) => void;
  onChapterTitleChange: (title: string) => void;
  onChapterSummaryChange: (summary: string) => void;
  onStartEditChapter: (chapter: OutlineChapter) => void;
  onCancelEditChapter: () => void;
  onSubmitChapter: (event: FormEvent<HTMLFormElement>, chapterId: string) => void;
  onDeleteChapter: (chapter: OutlineChapter) => void;
  onCreateScene: (chapterId: string, title: string) => void;
  onSelectScene: (sceneId: string) => void;
  onDeleteScene: (sceneId: string, sceneTitle: string) => void;
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
  onSectionTitleChange,
  onSectionTypeChange,
  onStartEditSection,
  onCancelEditSection,
  onSubmitSection,
  onDeleteSection,
  onCreateChapter,
  onChapterTitleChange,
  onChapterSummaryChange,
  onStartEditChapter,
  onCancelEditChapter,
  onSubmitChapter,
  onDeleteChapter,
  onCreateScene,
  onSelectScene,
  onDeleteScene,
}: SectionItemProps) {
  return (
    <section className="rounded-lg border border-zinc-200 bg-white shadow-sm">
      <div className="border-b border-zinc-100 bg-zinc-50 px-3 py-2">
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
            <div className="flex gap-2">
              <Button type="button" variant="secondary" size="sm" onClick={() => onStartEditSection(section)}>
                Editar
              </Button>
              <Button type="button" variant="ghost" size="sm" disabled={deleteSectionPending} onClick={() => onDeleteSection(section)}>
                Excluir
              </Button>
            </div>
          </div>
        )}
      </div>

      <div className="grid gap-3 p-3">
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
          <div className="grid gap-3">
            {section.chapters.map((chapter) => (
              <ChapterItem
                key={chapter.id}
                chapter={chapter}
                isEditing={editingChapterId === chapter.id}
                chapterTitle={chapterTitle}
                chapterSummary={chapterSummary}
                selectedSceneId={selectedSceneId}
                updatePending={updateChapterPending}
                deletePending={deleteChapterPending}
                createScenePending={createScenePending}
                deleteScenePending={deleteScenePending}
                onTitleChange={onChapterTitleChange}
                onSummaryChange={onChapterSummaryChange}
                onStartEdit={onStartEditChapter}
                onCancelEdit={onCancelEditChapter}
                onSubmit={onSubmitChapter}
                onDeleteChapter={onDeleteChapter}
                onCreateScene={onCreateScene}
                onSelectScene={onSelectScene}
                onDeleteScene={onDeleteScene}
              />
            ))}
          </div>
        )}
      </div>
    </section>
  );
}
