"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createChapter, createScene, createSection, getOutline } from "@/features/outline/api/outline-api";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { InlineCreateForm } from "@/features/outline/components/inline-create-form";
import { queryKeys } from "@/lib/query/keys";

type OutlineSidebarProps = {
  bookId: string;
  selectedSceneId: string | null;
  onSelectScene: (sceneId: string) => void;
};

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
    <aside className="flex h-full flex-col gap-4 border-r border-zinc-200 bg-white p-4">
      <div>
        <p className="text-xs font-medium uppercase text-zinc-500">Livro</p>
        <h1 className="mt-1 text-lg font-semibold text-zinc-950">{outline.title}</h1>
        <p className="mt-1 text-sm text-zinc-600">{outline.wordCount} palavras</p>
      </div>

      <InlineCreateForm
        placeholder="Nova seção"
        buttonLabel="Seção"
        disabled={sectionMutation.isPending}
        onCreate={(title) => sectionMutation.mutate(title)}
      />

      <div className="grid gap-4 overflow-auto pr-1">
        {outline.sections.map((section) => (
          <div key={section.id} className="grid gap-2">
            <div className="rounded-md bg-zinc-100 px-3 py-2">
              <div className="flex items-center justify-between gap-2">
                <h2 className="text-sm font-semibold text-zinc-900">{section.title}</h2>
                <span className="text-xs text-zinc-500">{section.wordCount}</span>
              </div>
            </div>

            <div className="pl-3">
              <InlineCreateForm
                placeholder="Novo capítulo"
                buttonLabel="Cap."
                disabled={chapterMutation.isPending}
                onCreate={(title) => chapterMutation.mutate({ sectionId: section.id, title })}
              />
            </div>

            <div className="grid gap-3 pl-3">
              {section.chapters.map((chapter) => (
                <div key={chapter.id} className="grid gap-2 border-l border-zinc-200 pl-3">
                  <div className="flex items-center justify-between gap-2">
                    <h3 className="text-sm font-medium text-zinc-800">{chapter.title}</h3>
                    <span className="text-xs text-zinc-500">{chapter.wordCount}</span>
                  </div>

                  <InlineCreateForm
                    placeholder="Nova cena"
                    buttonLabel="Cena"
                    disabled={sceneMutation.isPending}
                    onCreate={(title) => sceneMutation.mutate({ chapterId: chapter.id, title })}
                  />

                  <div className="grid gap-1">
                    {chapter.scenes.map((scene) => (
                      <button
                        key={scene.id}
                        type="button"
                        onClick={() => onSelectScene(scene.id)}
                        className={`rounded-md px-2 py-2 text-left text-sm transition ${
                          selectedSceneId === scene.id
                            ? "bg-zinc-900 text-white"
                            : "bg-white text-zinc-700 hover:bg-zinc-100"
                        }`}
                      >
                        <span className="block font-medium">{scene.title}</span>
                        <span className={selectedSceneId === scene.id ? "text-zinc-200" : "text-zinc-500"}>
                          {scene.status} · {scene.wordCount} palavras
                        </span>
                      </button>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>

      {sectionMutation.isError || chapterMutation.isError || sceneMutation.isError ? (
        <FeedbackMessage variant="error">Não foi possível criar o item.</FeedbackMessage>
      ) : null}
    </aside>
  );
}
