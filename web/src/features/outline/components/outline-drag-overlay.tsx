import type { ReactNode } from "react";
import type { OutlineChapter, OutlineScene, OutlineSection } from "@/features/outline/types";

type SectionDragPreviewProps = {
  section: OutlineSection;
};

type ChapterDragPreviewProps = {
  chapter: OutlineChapter;
};

type SceneDragPreviewProps = {
  scene: OutlineScene;
};

export function SectionDragPreview({ section }: SectionDragPreviewProps) {
  const scenesCount = section.chapters.reduce((total, chapter) => total + chapter.scenes.length, 0);

  return (
    <DragPreviewShell eyebrow={`Secao · ${section.type}`} title={section.title}>
      <PreviewMetric label="capitulos" value={section.chapters.length} />
      <PreviewMetric label="cenas" value={scenesCount} />
      <PreviewMetric label="palavras" value={section.wordCount} />
    </DragPreviewShell>
  );
}

export function ChapterDragPreview({ chapter }: ChapterDragPreviewProps) {
  return (
    <DragPreviewShell eyebrow="Capitulo" title={chapter.title}>
      <PreviewMetric label="cenas" value={chapter.scenes.length} />
      <PreviewMetric label="palavras" value={chapter.wordCount} />
    </DragPreviewShell>
  );
}

export function SceneDragPreview({ scene }: SceneDragPreviewProps) {
  return (
    <DragPreviewShell eyebrow="Cena" title={scene.title}>
      <span className="rounded-md border border-zinc-200 bg-zinc-50 px-2 py-1 text-[11px] font-medium uppercase text-zinc-600">
        {scene.status}
      </span>
      <PreviewMetric label="palavras" value={scene.wordCount} />
    </DragPreviewShell>
  );
}

function DragPreviewShell({ eyebrow, title, children }: { eyebrow: string; title: string; children: ReactNode }) {
  return (
    <div className="w-72 rounded-md border border-zinc-300 bg-white px-3 py-2 text-left shadow-lg shadow-zinc-900/15 ring-1 ring-zinc-950/5">
      <p className="text-[11px] font-medium uppercase text-zinc-500">{eyebrow}</p>
      <p className="mt-0.5 truncate text-sm font-semibold text-zinc-950">{title}</p>
      <div className="mt-2 flex flex-wrap gap-1.5">{children}</div>
    </div>
  );
}

function PreviewMetric({ label, value }: { label: string; value: number }) {
  return (
    <span className="rounded-md border border-zinc-200 bg-zinc-50 px-2 py-1 text-[11px] text-zinc-600">
      <span className="font-semibold tabular-nums text-zinc-900">{value}</span> {label}
    </span>
  );
}
