import type { OutlineScene } from "@/features/outline/types";

type SceneRowProps = {
  scene: OutlineScene;
  isSelected: boolean;
  deletePending: boolean;
  reorderPending: boolean;
  canMoveUp: boolean;
  canMoveDown: boolean;
  onSelect: (sceneId: string) => void;
  onDelete: (sceneId: string, sceneTitle: string) => void;
  onMoveUp: (sceneId: string) => void;
  onMoveDown: (sceneId: string) => void;
};

export function SceneRow({
  scene,
  isSelected,
  deletePending,
  reorderPending,
  canMoveUp,
  canMoveDown,
  onSelect,
  onDelete,
  onMoveUp,
  onMoveDown,
}: SceneRowProps) {
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
      <div className="flex items-stretch">
        <button
          type="button"
          aria-label={`Mover cena ${scene.title} para cima`}
          title="Mover para cima"
          onClick={() => onMoveUp(scene.id)}
          disabled={!canMoveUp || reorderPending}
          className={`px-1.5 text-xs font-medium transition ${
            isSelected ? "text-zinc-200 hover:text-white" : "text-zinc-500 hover:text-zinc-900"
          } disabled:opacity-40`}
        >
          ↑
        </button>
        <button
          type="button"
          aria-label={`Mover cena ${scene.title} para baixo`}
          title="Mover para baixo"
          onClick={() => onMoveDown(scene.id)}
          disabled={!canMoveDown || reorderPending}
          className={`px-1.5 text-xs font-medium transition ${
            isSelected ? "text-zinc-200 hover:text-white" : "text-zinc-500 hover:text-zinc-900"
          } disabled:opacity-40`}
        >
          ↓
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
    </div>
  );
}
