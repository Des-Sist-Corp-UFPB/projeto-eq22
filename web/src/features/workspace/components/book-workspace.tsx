"use client";

import { useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { CharactersPanel } from "@/features/characters/components/characters-panel";
import { BookDashboard } from "@/features/dashboard/components/book-dashboard";
import { ItemsPanel } from "@/features/items/components/items-panel";
import { LocationsPanel } from "@/features/locations/components/locations-panel";
import { getOutline } from "@/features/outline/api/outline-api";
import { OutlineSidebar } from "@/features/outline/components/outline-sidebar";
import { SceneEditor } from "@/features/scenes/components/scene-editor";
import { queryKeys } from "@/lib/query/keys";

type WorkspaceMode = "overview" | "scenes" | "characters" | "locations" | "items";

export function BookWorkspace({ bookId }: { bookId: string }) {
  const [selectedSceneId, setSelectedSceneId] = useState<string | null>(null);
  const [mode, setMode] = useState<WorkspaceMode>("scenes");
  const [isFocusMode, setIsFocusMode] = useState(false);
  const outlineQuery = useQuery({
    queryKey: queryKeys.outline(bookId),
    queryFn: () => getOutline(bookId),
  });

  const outline = outlineQuery.data;
  const isScenesFocusMode = mode === "scenes" && isFocusMode;

  function handleModeChange(nextMode: WorkspaceMode) {
    setMode(nextMode);
    if (nextMode !== "scenes") {
      setIsFocusMode(false);
    }
  }

  function handleSceneDeleted() {
    setSelectedSceneId(null);
    setIsFocusMode(false);
  }

  return (
    <main className={`grid h-screen overflow-hidden bg-zinc-50 text-zinc-950 ${isScenesFocusMode ? "grid-rows-[1fr]" : "grid-rows-[64px_1fr]"}`}>
      {isScenesFocusMode ? null : (
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
              variant={mode === "overview" ? "primary" : "ghost"}
              onClick={() => handleModeChange("overview")}
            >
              Visão geral
            </Button>
            <Button
              type="button"
              size="sm"
              variant={mode === "scenes" ? "primary" : "ghost"}
              onClick={() => handleModeChange("scenes")}
            >
              Cenas
            </Button>
            <Button
              type="button"
              size="sm"
              variant={mode === "characters" ? "primary" : "ghost"}
              onClick={() => handleModeChange("characters")}
            >
              Personagens
            </Button>
            <Button
              type="button"
              size="sm"
              variant={mode === "locations" ? "primary" : "ghost"}
              onClick={() => handleModeChange("locations")}
            >
              Localizações
            </Button>
            <Button type="button" size="sm" variant={mode === "items" ? "primary" : "ghost"} onClick={() => handleModeChange("items")}>
              Itens
            </Button>
          </div>
          {typeof outline?.wordCount === "number" ? <Badge>{outline.wordCount} palavras</Badge> : null}
          <Badge variant="outline" className="hidden sm:inline-flex">
            Backend: localhost:8085
          </Badge>
        </div>
      </header>
      )}

      <div className={`grid min-h-0 grid-cols-1 overflow-hidden ${isScenesFocusMode ? "" : "md:grid-cols-[340px_minmax(0,1fr)]"}`}>
        {mode === "scenes" && !isScenesFocusMode ? (
          <div className="min-h-0 overflow-hidden border-r border-zinc-200 bg-white">
            <OutlineSidebar bookId={bookId} selectedSceneId={selectedSceneId} onSelectScene={setSelectedSceneId} />
          </div>
        ) : null}

        <div className={`min-h-0 overflow-hidden bg-zinc-100/70 ${mode !== "scenes" || isScenesFocusMode ? "md:col-span-2" : ""}`}>
          {mode === "overview" ? (
            <BookDashboard bookId={bookId} />
          ) : mode === "scenes" ? (
            <SceneEditor
              bookId={bookId}
              sceneId={selectedSceneId}
              isFocusMode={isScenesFocusMode}
              onEnterFocusMode={() => setIsFocusMode(true)}
              onExitFocusMode={() => setIsFocusMode(false)}
              onSceneDeleted={handleSceneDeleted}
            />
          ) : mode === "characters" ? (
            <CharactersPanel bookId={bookId} />
          ) : mode === "locations" ? (
            <LocationsPanel bookId={bookId} />
          ) : (
            <ItemsPanel bookId={bookId} />
          )}
        </div>
      </div>
    </main>
  );
}
