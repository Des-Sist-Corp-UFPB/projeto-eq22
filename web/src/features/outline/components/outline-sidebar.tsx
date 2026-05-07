"use client";

import { type FormEvent, type ReactNode, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
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
import { InlineCreateForm } from "@/features/outline/components/inline-create-form";
import type { OutlineChapter, OutlineSection, SectionType } from "@/features/outline/types";
import { queryKeys } from "@/lib/query/keys";

type OutlineSidebarProps = {
  bookId: string;
  selectedSceneId: string | null;
  onSelectScene: (sceneId: string | null) => void;
};

const sectionTypes: SectionType[] = ["PART", "PROLOGUE", "INTERLUDE", "EPILOGUE", "OTHER"];

function WordCount({ count }: { count: number }) {
  return <span className="shrink-0 text-xs tabular-nums text-zinc-500">{count} palavras</span>;
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="grid gap-1 text-xs">
      <span className="font-medium text-zinc-600">{label}</span>
      {children}
    </label>
  );
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

  const outlineQuery = useQuery({
    queryKey: queryKeys.outline(bookId),
    queryFn: () => getOutline(bookId),
  });

  function invalidateOutline() {
    void queryClient.invalidateQueries({ queryKey: queryKeys.outline(bookId) });
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
      <aside className="flex h-full min-h-0 flex-col border-r border-zinc-200 bg-white p-3">
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
      <aside className="flex h-full min-h-0 flex-col border-r border-zinc-200 bg-white p-3">
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
    deleteSceneMutation.isError;

  return (
    <aside className="flex h-full min-h-0 flex-col border-r border-zinc-200 bg-white">
      <div className="border-b border-zinc-200 p-4">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="text-xs font-medium uppercase text-zinc-500">Livro</p>
            <h1 className="mt-1 truncate text-lg font-semibold text-zinc-950">{outline.title}</h1>
          </div>
          <Badge className="shrink-0">{outline.wordCount} palavras</Badge>
        </div>
      </div>

      <div className="grid gap-2 border-b border-zinc-200 bg-zinc-50/70 p-3">
        <InlineCreateForm
          ariaLabel="Nova seção"
          placeholder="Nova seção"
          buttonLabel="Criar"
          disabled={sectionMutation.isPending}
          onCreate={(title) => sectionMutation.mutate(title)}
        />
        {successMessage ? <FeedbackMessage variant="success">{successMessage}</FeedbackMessage> : null}
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto p-3">
        {outline.sections.length === 0 ? (
          <EmptyState
            size="sm"
            title="Nenhuma seção ainda"
            description="Crie uma seção para começar a organizar o esboço do livro."
          />
        ) : (
          <div className="grid gap-3">
            {outline.sections.map((section) => (
              <section key={section.id} className="rounded-lg border border-zinc-200 bg-white shadow-sm">
                <div className="border-b border-zinc-100 bg-zinc-50 px-3 py-2">
                  {editingSectionId === section.id ? (
                    <form onSubmit={(event) => handleSectionSubmit(event, section.id)} className="grid gap-2">
                      <Field label="Nome da seção">
                        <input
                          value={sectionTitle}
                          onChange={(event) => setSectionTitle(event.target.value)}
                          className="min-h-8 rounded-md border border-zinc-300 bg-white px-2 py-1 text-sm outline-none focus:border-zinc-800"
                        />
                      </Field>
                      <Field label="Tipo">
                        <select
                          value={sectionType}
                          onChange={(event) => setSectionType(event.target.value as SectionType)}
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
                        <Button type="submit" size="sm" disabled={updateSectionMutation.isPending || !sectionTitle.trim()}>
                          Salvar
                        </Button>
                        <Button type="button" size="sm" variant="secondary" onClick={() => setEditingSectionId(null)}>
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
                        <Button type="button" variant="secondary" size="sm" onClick={() => startEditingSection(section)}>
                          Editar
                        </Button>
                        <Button
                          type="button"
                          variant="ghost"
                          size="sm"
                          disabled={deleteSectionMutation.isPending}
                          onClick={() => handleDeleteSection(section)}
                        >
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
                    disabled={chapterMutation.isPending}
                    onCreate={(title) => chapterMutation.mutate({ sectionId: section.id, title })}
                  />

                  {section.chapters.length === 0 ? (
                    <EmptyState size="sm" title="Nenhum capítulo" description="Esta seção ainda não tem capítulos." />
                  ) : (
                    <div className="grid gap-3">
                      {section.chapters.map((chapter) => (
                        <article key={chapter.id} className="grid gap-2 border-l-2 border-zinc-200 pl-3">
                          {editingChapterId === chapter.id ? (
                            <form onSubmit={(event) => handleChapterSubmit(event, chapter.id)} className="grid gap-2">
                              <Field label="Título do capítulo">
                                <input
                                  value={chapterTitle}
                                  onChange={(event) => setChapterTitle(event.target.value)}
                                  className="min-h-8 rounded-md border border-zinc-300 bg-white px-2 py-1 text-sm outline-none focus:border-zinc-800"
                                />
                              </Field>
                              <Field label="Resumo">
                                <textarea
                                  value={chapterSummary}
                                  rows={2}
                                  onChange={(event) => setChapterSummary(event.target.value)}
                                  className="rounded-md border border-zinc-300 bg-white px-2 py-1 text-sm outline-none focus:border-zinc-800"
                                />
                              </Field>
                              <div className="flex gap-2">
                                <Button type="submit" size="sm" disabled={updateChapterMutation.isPending || !chapterTitle.trim()}>
                                  Salvar
                                </Button>
                                <Button type="button" size="sm" variant="secondary" onClick={() => setEditingChapterId(null)}>
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
                                  {chapter.summary ? (
                                    <p className="mt-1 line-clamp-2 text-xs leading-5 text-zinc-500">{chapter.summary}</p>
                                  ) : null}
                                </div>
                                <WordCount count={chapter.wordCount} />
                              </div>
                              <div className="flex gap-2">
                                <Button type="button" variant="secondary" size="sm" onClick={() => startEditingChapter(chapter)}>
                                  Editar
                                </Button>
                                <Button
                                  type="button"
                                  variant="ghost"
                                  size="sm"
                                  disabled={deleteChapterMutation.isPending}
                                  onClick={() => handleDeleteChapter(chapter)}
                                >
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
                            disabled={sceneMutation.isPending}
                            onCreate={(title) => sceneMutation.mutate({ chapterId: chapter.id, title })}
                          />

                          {chapter.scenes.length === 0 ? (
                            <EmptyState size="sm" title="Nenhuma cena" description="Este capítulo ainda não tem cenas." />
                          ) : (
                            <div className="grid gap-1">
                              {chapter.scenes.map((scene) => {
                                const isSelected = selectedSceneId === scene.id;

                                return (
                                  <div
                                    key={scene.id}
                                    className={`grid grid-cols-[minmax(0,1fr)_auto] items-stretch rounded-md border transition ${
                                      isSelected
                                        ? "border-zinc-900 bg-zinc-900 text-white shadow-sm"
                                        : "border-transparent bg-white text-zinc-700 hover:border-zinc-200 hover:bg-zinc-50"
                                    }`}
                                  >
                                    <button
                                      type="button"
                                      onClick={() => onSelectScene(scene.id)}
                                      className="min-w-0 px-2.5 py-2 text-left text-sm focus:outline-none focus:ring-2 focus:ring-zinc-800 focus:ring-offset-1"
                                    >
                                      <span className="block truncate font-medium">{scene.title}</span>
                                      <span
                                        className={`mt-0.5 flex items-center justify-between gap-2 text-xs ${
                                          isSelected ? "text-zinc-200" : "text-zinc-500"
                                        }`}
                                      >
                                        <span>{scene.status}</span>
                                        <span className="tabular-nums">{scene.wordCount} palavras</span>
                                      </span>
                                    </button>
                                    <button
                                      type="button"
                                      onClick={() => handleDeleteScene(scene.id, scene.title)}
                                      disabled={deleteSceneMutation.isPending}
                                      className={`px-2 text-xs font-medium transition ${
                                        isSelected ? "text-zinc-200 hover:text-white" : "text-zinc-500 hover:text-red-700"
                                      } disabled:opacity-60`}
                                    >
                                      Excluir
                                    </button>
                                  </div>
                                );
                              })}
                            </div>
                          )}
                        </article>
                      ))}
                    </div>
                  )}
                </div>
              </section>
            ))}
          </div>
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
