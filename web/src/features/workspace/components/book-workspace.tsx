"use client";

import { useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { CharactersPanel } from "@/features/characters/components/characters-panel";
import { getOutline } from "@/features/outline/api/outline-api";
import { OutlineSidebar } from "@/features/outline/components/outline-sidebar";
import { SceneEditor } from "@/features/scenes/components/scene-editor";
import { queryKeys } from "@/lib/query/keys";

type WorkspaceMode = "scenes" | "characters";

export function BookWorkspace({ bookId }: { bookId: string }) {
  const [selectedSceneId, setSelectedSceneId] = useState<string | null>(null);
  const [mode, setMode] = useState<WorkspaceMode>("scenes");
  const outlineQuery = useQuery({
    queryKey: queryKeys.outline(bookId),
    queryFn: () => getOutline(bookId),
  });

  const outline = outlineQuery.data;

  return (
    <main className="grid h-screen grid-rows-[64px_1fr] overflow-hidden bg-zinc-50 text-zinc-950">
      <header className="flex min-w-0 items-center justify-between gap-4 border-b border-zinc-200 bg-white px-4 shadow-sm shadow-zinc-200/60 md:px-6">
        <div className="flex min-w-0 items-center gap-4">
          <Link
            href="/"
            className="inline-flex min-h-9 shrink-0 items-center justify-center rounded-md border border-zinc-300 bg-white px-3 py-2 text-sm font-medium text-zinc-700 transition hover:bg-zinc-100 hover:text-zinc-950"
          >
            Voltar
          </Link>

          <div className="min-w-0">
            <p className="text-xs font-medium uppercase text-zinc-500">Workspace</p>
            <h1 className="truncate text-base font-semibold text-zinc-950 md:text-lg">
              {outline?.title ?? "Carregando livro..."}
            </h1>
          </div>
        </div>

        <div className="flex shrink-0 items-center gap-2">
          <div className="flex rounded-md border border-zinc-200 bg-zinc-50 p-1">
            <Button
              type="button"
              size="sm"
              variant={mode === "scenes" ? "primary" : "ghost"}
              onClick={() => setMode("scenes")}
            >
              Cenas
            </Button>
            <Button
              type="button"
              size="sm"
              variant={mode === "characters" ? "primary" : "ghost"}
              onClick={() => setMode("characters")}
            >
              Personagens
            </Button>
          </div>
          {typeof outline?.wordCount === "number" ? <Badge>{outline.wordCount} palavras</Badge> : null}
          <Badge variant="outline" className="hidden sm:inline-flex">
            Backend: localhost:8085
          </Badge>
        </div>
      </header>

      <div className="grid min-h-0 grid-cols-1 overflow-hidden md:grid-cols-[340px_minmax(0,1fr)]">
        {mode === "scenes" ? (
          <div className="min-h-0 overflow-hidden border-r border-zinc-200 bg-white">
            <OutlineSidebar bookId={bookId} selectedSceneId={selectedSceneId} onSelectScene={setSelectedSceneId} />
          </div>
        ) : null}

        <div className={`min-h-0 overflow-hidden bg-zinc-100/70 ${mode === "characters" ? "md:col-span-2" : ""}`}>
          {mode === "scenes" ? (
            <SceneEditor bookId={bookId} sceneId={selectedSceneId} onSceneDeleted={() => setSelectedSceneId(null)} />
          ) : (
            <CharactersPanel bookId={bookId} />
          )}
        </div>
      </div>
    </main>
  );
}
