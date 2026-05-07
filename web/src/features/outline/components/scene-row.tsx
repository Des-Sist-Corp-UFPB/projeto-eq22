import type { OutlineScene } from "@/features/outline/types";

type SceneRowProps = {
  scene: OutlineScene;
  isSelected: boolean;
  deletePending: boolean;
  onSelect: (sceneId: string) => void;
  onDelete: (sceneId: string, sceneTitle: string) => void;
};

export function SceneRow({ scene, isSelected, deletePending, onSelect, onDelete }: SceneRowProps) {
  return (
    <div
      className={`grid grid-cols-[minmax(0,1fr)_auto] items-stretch rounded-md border transition ${
        isSelected
          ? "border-zinc-900 bg-zinc-900 text-white shadow-sm"
          : "border-transparent bg-white text-zinc-700 hover:border-zinc-200 hover:bg-zinc-50"
      }`}
    >
      <button
        type="button"
        onClick={() => onSelect(scene.id)}
        className="min-w-0 px-2.5 py-2 text-left text-sm focus:outline-none focus:ring-2 focus:ring-zinc-800 focus:ring-offset-1"
      >
        <span className="block truncate font-medium">{scene.title}</span>
        <span className={`mt-0.5 flex items-center justify-between gap-2 text-xs ${isSelected ? "text-zinc-200" : "text-zinc-500"}`}>
          <span>{scene.status}</span>
          <span className="tabular-nums">{scene.wordCount} palavras</span>
        </span>
      </button>
      <button
        type="button"
        onClick={() => onDelete(scene.id, scene.title)}
        disabled={deletePending}
        className={`px-2 text-xs font-medium transition ${
          isSelected ? "text-zinc-200 hover:text-white" : "text-zinc-500 hover:text-red-700"
        } disabled:opacity-60`}
      >
        Excluir
      </button>
    </div>
  );
}
