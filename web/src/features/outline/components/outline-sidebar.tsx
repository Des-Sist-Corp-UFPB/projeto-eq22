"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Badge } from "@/components/ui/badge";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { createChapter, createScene, createSection, getOutline } from "@/features/outline/api/outline-api";
import { InlineCreateForm } from "@/features/outline/components/inline-create-form";
import { queryKeys } from "@/lib/query/keys";

type OutlineSidebarProps = {
  bookId: string;
  selectedSceneId: string | null;
  onSelectScene: (sceneId: string) => void;
};

function SmallEmptyState({ message }: { message: string }) {
  return (
    <div className="rounded-md border border-dashed border-zinc-300 bg-zinc-50 px-3 py-2 text-xs leading-5 text-zinc-500">
      {message}
    </div>
  );
}

function WordCount({ count }: { count: number }) {
  return <span className="shrink-0 text-xs tabular-nums text-zinc-500">{count} palavras</span>;
}

export function OutlineSidebar({ bookId, selectedSceneId, onSelectScene }: OutlineSidebarProps) {
  const queryClient = useQueryClient();
  const outlineQuery = useQuery({
    queryKey: queryKeys.outline(bookId),
    queryFn: () => getOutline(bookId),
  });

  const sectionMutation = useMutation({
    mutationFn: (title: string) => createSection(bookId, { title, type: "PART" }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.outline(bookId) });
    },
  });

  const chapterMutation = useMutation({
    mutationFn: ({ sectionId, title }: { sectionId: string; title: string }) => createChapter(sectionId, { title }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.outline(bookId) });
    },
  });

  const sceneMutation = useMutation({
    mutationFn: ({ chapterId, title }: { chapterId: string; title: string }) =>
      createScene(chapterId, { title, status: "IDEA", contentText: "", contentJson: "" }),
    onSuccess: (scene) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.outline(bookId) });
      onSelectScene(scene.id);
    },
  });

  if (outlineQuery.isLoading) {
    return <LoadingState label="Carregando outline..." />;
  }

  if (outlineQuery.isError) {
    return <ErrorState message={outlineQuery.error.message} />;
  }

  const outline = outlineQuery.data;

  if (!outline) {
    return null;
  }

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

      <div className="border-b border-zinc-200 bg-zinc-50/70 p-3">
        <InlineCreateForm
          ariaLabel="Nova seção"
          placeholder="Nova seção"
          buttonLabel="Criar"
          disabled={sectionMutation.isPending}
          onCreate={(title) => sectionMutation.mutate(title)}
        />
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto p-3">
        {outline.sections.length === 0 ? (
          <SmallEmptyState message="Este livro ainda não tem seções. Crie uma seção para começar o esboço." />
        ) : (
          <div className="grid gap-3">
            {outline.sections.map((section) => (
              <section key={section.id} className="rounded-lg border border-zinc-200 bg-white shadow-sm">
                <div className="border-b border-zinc-100 bg-zinc-50 px-3 py-2">
                  <div className="flex items-center justify-between gap-3">
                    <div className="min-w-0">
                      <p className="text-[11px] font-medium uppercase text-zinc-500">Seção</p>
                      <h2 className="truncate text-sm font-semibold text-zinc-900">{section.title}</h2>
                    </div>
                    <WordCount count={section.wordCount} />
                  </div>
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
                    <SmallEmptyState message="Esta seção ainda não tem capítulos." />
                  ) : (
                    <div className="grid gap-3">
                      {section.chapters.map((chapter) => (
                        <article key={chapter.id} className="grid gap-2 border-l-2 border-zinc-200 pl-3">
                          <div className="flex items-start justify-between gap-3">
                            <div className="min-w-0">
                              <p className="text-[11px] font-medium uppercase text-zinc-500">Capítulo</p>
                              <h3 className="truncate text-sm font-medium text-zinc-800">{chapter.title}</h3>
                            </div>
                            <WordCount count={chapter.wordCount} />
                          </div>

                          <InlineCreateForm
                            compact
                            ariaLabel={`Nova cena em ${chapter.title}`}
                            placeholder="Nova cena"
                            buttonLabel="Cena"
                            disabled={sceneMutation.isPending}
                            onCreate={(title) => sceneMutation.mutate({ chapterId: chapter.id, title })}
                          />

                          {chapter.scenes.length === 0 ? (
                            <SmallEmptyState message="Este capítulo ainda não tem cenas." />
                          ) : (
                            <div className="grid gap-1">
                              {chapter.scenes.map((scene) => {
                                const isSelected = selectedSceneId === scene.id;

                                return (
                                  <button
                                    key={scene.id}
                                    type="button"
                                    onClick={() => onSelectScene(scene.id)}
                                    className={`group rounded-md border px-2.5 py-2 text-left text-sm transition focus:outline-none focus:ring-2 focus:ring-zinc-800 focus:ring-offset-1 ${
                                      isSelected
                                        ? "border-zinc-900 bg-zinc-900 text-white shadow-sm"
                                        : "border-transparent bg-white text-zinc-700 hover:border-zinc-200 hover:bg-zinc-50"
                                    }`}
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

      {sectionMutation.isError || chapterMutation.isError || sceneMutation.isError ? (
        <div className="border-t border-zinc-200 p-3">
          <FeedbackMessage variant="error">Não foi possível criar o item.</FeedbackMessage>
        </div>
      ) : null}
    </aside>
  );
}
