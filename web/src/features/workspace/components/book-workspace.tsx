"use client";

import { useState } from "react";
import Link from "next/link";
import { OutlineSidebar } from "@/features/outline/components/outline-sidebar";
import { SceneEditor } from "@/features/scenes/components/scene-editor";

export function BookWorkspace({ bookId }: { bookId: string }) {
  const [selectedSceneId, setSelectedSceneId] = useState<string | null>(null);

  return (
    <main className="grid h-screen grid-rows-[48px_1fr] overflow-hidden bg-zinc-50">
      <header className="flex items-center justify-between border-b border-zinc-200 bg-white px-4">
        <Link href="/" className="text-sm font-medium text-zinc-700 hover:text-zinc-950">
          Voltar
        </Link>
        <span className="text-sm text-zinc-500">Backend: localhost:8085</span>
      </header>
      <div className="grid min-h-0 grid-cols-1 md:grid-cols-[360px_1fr]">
        <OutlineSidebar bookId={bookId} selectedSceneId={selectedSceneId} onSelectScene={setSelectedSceneId} />
        <SceneEditor bookId={bookId} sceneId={selectedSceneId} />
      </div>
    </main>
  );
}
