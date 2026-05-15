"use client";

import { type FormEvent, useEffect, useState } from "react";
import {
  closestCenter,
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  type DragEndEvent,
  type DragStartEvent,
  useSensor,
  useSensors,
} from "@dnd-kit/core";
import { SortableContext, sortableKeyboardCoordinates, verticalListSortingStrategy } from "@dnd-kit/sortable";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Badge } from "@/components/ui/badge";
import { EmptyState } from "@/components/ui/empty-state";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { LoadingState } from "@/components/ui/feedback";
import {
  createChapter,
  createScene,
  createSection,
  deleteChapter,
  deleteScene,
  deleteSection,
  getOutline,
  updateChapter,
  updateSection,
} from "@/features/outline/api/outline-api";
import {
  useReorderChaptersMutation,
  useReorderScenesMutation,
  useReorderSectionsMutation,
} from "@/features/outline/api/outline-reorder-mutations";
import { InlineCreateForm } from "@/features/outline/components/inline-create-form";
import { SectionDragPreview } from "@/features/outline/components/outline-drag-overlay";
import { SectionItem } from "@/features/outline/components/section-item";
import type { OutlineChapter, OutlineSection, SectionType } from "@/features/outline/types";
import { getReorderedIds } from "@/features/outline/utils/reorder";
import { queryKeys } from "@/lib/query/keys";

type OutlineSidebarProps = {
  bookId: string;
  selectedSceneId: string | null;
  onSelectScene: (sceneId: string | null) => void;
};

const sectionTypes: SectionType[] = ["PART", "PROLOGUE", "INTERLUDE", "EPILOGUE", "OTHER"];

function moveItemId(items: { id: string }[], itemId: string, direction: -1 | 1) {
  const currentIndex = items.findIndex((item) => item.id === itemId);
  const nextIndex = currentIndex + direction;

  if (currentIndex < 0 || nextIndex < 0 || nextIndex >= items.length) {
    return null;
  }

  const orderedIds = items.map((item) => item.id);
  const [movedId] = orderedIds.splice(currentIndex, 1);
  orderedIds.splice(nextIndex, 0, movedId);

  return orderedIds;
}

export function OutlineSidebar({ bookId, selectedSceneId, onSelectScene }: OutlineSidebarProps) {
  const queryClient = useQueryClient();
  const [successMessage, setSuccessMessage] = useState("");
  const [editingSectionId, setEditingSectionId] = useState<string | null>(null);
  const [sectionTitle, setSectionTitle] = useState("");
  const [sectionType, setSectionType] = useState<SectionType>("PART");
  const [editingChapterId, setEditingChapterId] = useState<string | null>(null);
  const [chapterTitle, setChapterTitle] = useState("");
  const [chapterSummary, setChapterSummary] = useState("");
  const [collapsedSectionIds, setCollapsedSectionIds] = useState<Set<string>>(() => new Set());
  const [collapsedChapterIds, setCollapsedChapterIds] = useState<Set<string>>(() => new Set());
  const [activeSectionId, setActiveSectionId] = useState<string | null>(null);

  const outlineQuery = useQuery({
    queryKey: queryKeys.outline(bookId),
    queryFn: () => getOutline(bookId),
  });
  const reorderSectionsMutation = useReorderSectionsMutation(bookId);
  const reorderChaptersMutation = useReorderChaptersMutation(bookId);
  const reorderScenesMutation = useReorderScenesMutation(bookId);
  const sectionSensors = useSensors(
    useSensor(PointerSensor, {
      activationConstraint: {
        distance: 8,
      },
    }),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    })
  );

  useEffect(() => {
    const outline = outlineQuery.data;
    if (!outline || !selectedSceneId) {
      return;
    }

    const selectedSection = outline.sections.find((section) =>
      section.chapters.some((chapter) => chapter.scenes.some((scene) => scene.id === selectedSceneId))
    );
    const selectedChapter = selectedSection?.chapters.find((chapter) =>
      chapter.scenes.some((scene) => scene.id === selectedSceneId)
    );

    if (selectedSection) {
      setCollapsedSectionIds((current) => {
        const next = new Set(current);
        next.delete(selectedSection.id);
        return next;
      });
    }
    if (selectedChapter) {
      setCollapsedChapterIds((current) => {
        const next = new Set(current);
        next.delete(selectedChapter.id);
        return next;
      });
    }
  }, [outlineQuery.data, selectedSceneId]);

  function invalidateOutline() {
    void queryClient.invalidateQueries({ queryKey: queryKeys.outline(bookId) });
  }

  function toggleSection(sectionId: string) {
    setCollapsedSectionIds((current) => toggleSetId(current, sectionId));
  }

  function toggleChapter(chapterId: string) {
    setCollapsedChapterIds((current) => toggleSetId(current, chapterId));
  }

  const sectionMutation = useMutation({
    mutationFn: (title: string) => createSection(bookId, { title, type: "PART" }),
    onMutate: () => setSuccessMessage(""),
    onSuccess: () => {
      setSuccessMessage("Seção criada com sucesso.");
      invalidateOutline();
    },
  });

  const updateSectionMutation = useMutation({
    mutationFn: ({ sectionId, title, type }: { sectionId: string; title: string; type: SectionType }) =>
      updateSection(sectionId, { title, type }),
    onMutate: () => setSuccessMessage(""),
    onSuccess: () => {
      setSuccessMessage("Seção atualizada com sucesso.");
      setEditingSectionId(null);
      invalidateOutline();
    },
  });

  const deleteSectionMutation = useMutation({
    mutationFn: (sectionId: string) => deleteSection(sectionId),
    onMutate: () => setSuccessMessage(""),
    onSuccess: () => {
      setSuccessMessage("Seção excluída com sucesso.");
      invalidateOutline();
    },
  });

  const chapterMutation = useMutation({
    mutationFn: ({ sectionId, title }: { sectionId: string; title: string }) => createChapter(sectionId, { title }),
    onMutate: () => setSuccessMessage(""),
    onSuccess: () => {
      setSuccessMessage("Capítulo criado com sucesso.");
      invalidateOutline();
    },
  });

  const updateChapterMutation = useMutation({
    mutationFn: ({ chapterId, title, summary }: { chapterId: string; title: string; summary: string }) =>
      updateChapter(chapterId, { title, summary }),
    onMutate: () => setSuccessMessage(""),
    onSuccess: () => {
      setSuccessMessage("Capítulo atualizado com sucesso.");
      setEditingChapterId(null);
      invalidateOutline();
    },
  });

  const deleteChapterMutation = useMutation({
    mutationFn: (chapterId: string) => deleteChapter(chapterId),
    onMutate: () => setSuccessMessage(""),
    onSuccess: () => {
      setSuccessMessage("Capítulo excluído com sucesso.");
      invalidateOutline();
    },
  });

  const sceneMutation = useMutation({
    mutationFn: ({ chapterId, title }: { chapterId: string; title: string }) =>
      createScene(chapterId, { title, status: "IDEA", contentText: "", contentJson: "" }),
    onMutate: () => setSuccessMessage(""),
    onSuccess: (scene) => {
      setSuccessMessage("Cena criada com sucesso.");
      invalidateOutline();
      onSelectScene(scene.id);
    },
  });

  const deleteSceneMutation = useMutation({
    mutationFn: (sceneId: string) => deleteScene(sceneId),
    onMutate: () => setSuccessMessage(""),
    onSuccess: (_data, sceneId) => {
      setSuccessMessage("Cena excluída com sucesso.");
      if (selectedSceneId === sceneId) {
        onSelectScene(null);
        queryClient.removeQueries({ queryKey: queryKeys.scene(sceneId) });
      }
      invalidateOutline();
    },
  });

  function handleMoveSection(sectionId: string, direction: -1 | 1) {
    if (!outline) {
      return;
    }

    const orderedIds = moveItemId(outline.sections, sectionId, direction);
    if (!orderedIds) {
      return;
    }

    setSuccessMessage("");
    reorderSectionsMutation.mutate(
      { orderedIds },
      {
        onSuccess: () => setSuccessMessage("Ordem das seções atualizada."),
      }
    );
  }

  function handleMoveChapter(section: OutlineSection, chapterId: string, direction: -1 | 1) {
    const orderedIds = moveItemId(section.chapters, chapterId, direction);
    if (!orderedIds) {
      return;
    }

    setSuccessMessage("");
    reorderChaptersMutation.mutate(
      { sectionId: section.id, orderedIds },
      {
        onSuccess: () => setSuccessMessage("Ordem dos capítulos atualizada."),
      }
    );
  }

  function handleSectionDragEnd(event: DragEndEvent) {
    setActiveSectionId(null);

    const outline = outlineQuery.data;
    if (!outline) {
      return;
    }

    const orderedIds = getReorderedIds(outline.sections, String(event.active.id), event.over ? String(event.over.id) : null);
    if (!orderedIds) {
      return;
    }

    handleReorderSections(orderedIds);
  }

  function handleSectionDragStart(event: DragStartEvent) {
    setActiveSectionId(String(event.active.id));
  }

  function handleSectionDragCancel() {
    setActiveSectionId(null);
  }

  function handleReorderSections(orderedIds: string[]) {
    setSuccessMessage("");
    reorderSectionsMutation.mutate(
      { orderedIds },
      {
        onSuccess: () => setSuccessMessage("Ordem das seÃ§Ãµes atualizada."),
      }
    );
  }

  function handleReorderChapters(section: OutlineSection, orderedIds: string[]) {
    setSuccessMessage("");
    reorderChaptersMutation.mutate(
      { sectionId: section.id, orderedIds },
      {
        onSuccess: () => setSuccessMessage("Ordem dos capÃ­tulos atualizada."),
      }
    );
  }

  function handleMoveScene(chapter: OutlineChapter, sceneId: string, direction: -1 | 1) {
    const orderedIds = moveItemId(chapter.scenes, sceneId, direction);
    if (!orderedIds) {
      return;
    }

    setSuccessMessage("");
    reorderScenesMutation.mutate(
      { chapterId: chapter.id, orderedIds },
      {
        onSuccess: () => setSuccessMessage("Ordem das cenas atualizada."),
      }
    );
  }

  function handleReorderScenes(chapter: OutlineChapter, orderedIds: string[]) {
    setSuccessMessage("");
    reorderScenesMutation.mutate(
      { chapterId: chapter.id, orderedIds },
      {
        onSuccess: () => setSuccessMessage("Ordem das cenas atualizada."),
      }
    );
  }

  function startEditingSection(section: OutlineSection) {
    setEditingSectionId(section.id);
    setSectionTitle(section.title);
    setSectionType(section.type);
  }

  function handleSectionSubmit(event: FormEvent<HTMLFormElement>, sectionId: string) {
    event.preventDefault();
    if (!sectionTitle.trim()) {
      return;
    }
    updateSectionMutation.mutate({ sectionId, title: sectionTitle.trim(), type: sectionType });
  }

  function handleDeleteSection(section: OutlineSection) {
    const confirmed = window.confirm(
      `Excluir a seção "${section.title}"? Esta ação pode remover capítulos e cenas desta seção.`
    );
    if (!confirmed) {
      return;
    }

    if (selectedSceneId && section.chapters.some((chapter) => chapter.scenes.some((scene) => scene.id === selectedSceneId))) {
      onSelectScene(null);
    }
    deleteSectionMutation.mutate(section.id);
  }

  function startEditingChapter(chapter: OutlineChapter) {
    setEditingChapterId(chapter.id);
    setChapterTitle(chapter.title);
    setChapterSummary(chapter.summary ?? "");
  }

  function handleChapterSubmit(event: FormEvent<HTMLFormElement>, chapterId: string) {
    event.preventDefault();
    if (!chapterTitle.trim()) {
      return;
    }
    updateChapterMutation.mutate({ chapterId, title: chapterTitle.trim(), summary: chapterSummary.trim() });
  }

  function handleDeleteChapter(chapter: OutlineChapter) {
    const confirmed = window.confirm(`Excluir o capítulo "${chapter.title}"? Esta ação pode remover cenas deste capítulo.`);
    if (!confirmed) {
      return;
    }

    if (selectedSceneId && chapter.scenes.some((scene) => scene.id === selectedSceneId)) {
      onSelectScene(null);
    }
    deleteChapterMutation.mutate(chapter.id);
  }

  function handleDeleteScene(sceneId: string, sceneTitle: string) {
    const confirmed = window.confirm(`Excluir a cena "${sceneTitle}"? Esta ação não pode ser desfeita nesta etapa.`);
    if (confirmed) {
      deleteSceneMutation.mutate(sceneId);
    }
  }

  if (outlineQuery.isLoading) {
    return <LoadingState label="Carregando outline..." />;
  }

  if (outlineQuery.isError) {
    return (
      <aside className="flex h-full min-h-0 flex-col bg-white p-3">
        <EmptyState
          size="sm"
          title="Não foi possível carregar o outline"
          description="Verifique se o backend está rodando e tente abrir o livro novamente."
        />
        <FeedbackMessage variant="error" className="mt-3">
          Erro ao carregar outline.
        </FeedbackMessage>
      </aside>
    );
  }

  const outline = outlineQuery.data;

  if (!outline) {
    return (
      <aside className="flex h-full min-h-0 flex-col bg-white p-3">
        <EmptyState size="sm" title="Outline indisponível" description="Não recebemos dados deste livro." />
      </aside>
    );
  }

  const actionError =
    sectionMutation.isError ||
    updateSectionMutation.isError ||
    deleteSectionMutation.isError ||
    chapterMutation.isError ||
    updateChapterMutation.isError ||
    deleteChapterMutation.isError ||
    sceneMutation.isError ||
    deleteSceneMutation.isError ||
    reorderSectionsMutation.isError ||
    reorderChaptersMutation.isError ||
    reorderScenesMutation.isError;
  const activeSection = activeSectionId ? outline.sections.find((section) => section.id === activeSectionId) : null;

  return (
    <aside className="flex h-full min-h-0 flex-col bg-white">
      <div className="border-b border-zinc-200 bg-white px-4 py-5">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="text-xs font-medium uppercase text-zinc-500">Livro</p>
            <h1 className="mt-1 truncate text-lg font-semibold text-zinc-950">{outline.title}</h1>
          </div>
          <Badge className="shrink-0">{outline.wordCount} palavras</Badge>
        </div>
      </div>

      <div className="grid gap-2 border-b border-zinc-200 bg-white px-4 py-3">
        <InlineCreateForm
          ariaLabel="Nova seção"
          placeholder="Nova seção"
          buttonLabel="Criar"
          disabled={sectionMutation.isPending}
          onCreate={(title) => sectionMutation.mutate(title)}
        />
        {successMessage ? <FeedbackMessage variant="success">{successMessage}</FeedbackMessage> : null}
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto bg-zinc-50/70 px-3 py-4">
        {outline.sections.length === 0 ? (
          <EmptyState
            size="sm"
            title="Nenhuma seção ainda"
            description="Crie uma seção para começar a organizar o esboço do livro."
          />
        ) : (
          <DndContext
            sensors={sectionSensors}
            collisionDetection={closestCenter}
            onDragStart={handleSectionDragStart}
            onDragEnd={handleSectionDragEnd}
            onDragCancel={handleSectionDragCancel}
          >
            <SortableContext items={outline.sections.map((section) => section.id)} strategy={verticalListSortingStrategy}>
              <div className="grid gap-4">
                {outline.sections.map((section, sectionIndex) => (
                  <SectionItem
                    key={section.id}
                    section={section}
                    canMoveUp={sectionIndex > 0}
                    canMoveDown={sectionIndex < outline.sections.length - 1}
                    isCollapsed={collapsedSectionIds.has(section.id)}
                    collapsedChapterIds={collapsedChapterIds}
                    sectionTypes={sectionTypes}
                    selectedSceneId={selectedSceneId}
                    editingSectionId={editingSectionId}
                    sectionTitle={sectionTitle}
                    sectionType={sectionType}
                    editingChapterId={editingChapterId}
                    chapterTitle={chapterTitle}
                    chapterSummary={chapterSummary}
                    updateSectionPending={updateSectionMutation.isPending}
                    deleteSectionPending={deleteSectionMutation.isPending}
                    createChapterPending={chapterMutation.isPending}
                    updateChapterPending={updateChapterMutation.isPending}
                    deleteChapterPending={deleteChapterMutation.isPending}
                    createScenePending={sceneMutation.isPending}
                    deleteScenePending={deleteSceneMutation.isPending}
                    reorderSectionPending={reorderSectionsMutation.isPending}
                    reorderChapterPending={reorderChaptersMutation.isPending}
                    reorderScenePending={reorderScenesMutation.isPending}
                    onSectionTitleChange={setSectionTitle}
                    onSectionTypeChange={setSectionType}
                    onStartEditSection={startEditingSection}
                    onCancelEditSection={() => setEditingSectionId(null)}
                    onSubmitSection={handleSectionSubmit}
                    onDeleteSection={handleDeleteSection}
                    onMoveSectionUp={(sectionId) => handleMoveSection(sectionId, -1)}
                    onMoveSectionDown={(sectionId) => handleMoveSection(sectionId, 1)}
                    onToggleSection={toggleSection}
                    onToggleChapter={toggleChapter}
                    onCreateChapter={(sectionId, title) => chapterMutation.mutate({ sectionId, title })}
                    onChapterTitleChange={setChapterTitle}
                    onChapterSummaryChange={setChapterSummary}
                    onStartEditChapter={startEditingChapter}
                    onCancelEditChapter={() => setEditingChapterId(null)}
                    onSubmitChapter={handleChapterSubmit}
                    onDeleteChapter={handleDeleteChapter}
                    onMoveChapterUp={(section, chapterId) => handleMoveChapter(section, chapterId, -1)}
                    onMoveChapterDown={(section, chapterId) => handleMoveChapter(section, chapterId, 1)}
                    onReorderChapters={handleReorderChapters}
                    onCreateScene={(chapterId, title) => sceneMutation.mutate({ chapterId, title })}
                    onSelectScene={(sceneId) => onSelectScene(sceneId)}
                    onDeleteScene={handleDeleteScene}
                    onMoveSceneUp={(chapter, sceneId) => handleMoveScene(chapter, sceneId, -1)}
                    onMoveSceneDown={(chapter, sceneId) => handleMoveScene(chapter, sceneId, 1)}
                    onReorderScenes={handleReorderScenes}
                  />
                ))}
              </div>
            </SortableContext>
            <DragOverlay>{activeSection ? <SectionDragPreview section={activeSection} /> : null}</DragOverlay>
          </DndContext>
        )}
      </div>

      {actionError ? (
        <div className="border-t border-zinc-200 p-3">
          <FeedbackMessage variant="error">
            Não foi possível concluir a ação agora. Verifique a API e tente novamente.
          </FeedbackMessage>
        </div>
      ) : null}
    </aside>
  );
}

function toggleSetId(current: Set<string>, id: string) {
  const next = new Set(current);
  if (next.has(id)) {
    next.delete(id);
  } else {
    next.add(id);
  }
  return next;
}
