import { EmptyState } from "@/components/ui/empty-state";
import type { CharacterResponse } from "@/features/characters/types";
import type { ItemResponse } from "@/features/items/types";

type ItemsListProps = {
  items: ItemResponse[];
  characters: CharacterResponse[];
  selectedItemId: string | null;
  onEditItem: (item: ItemResponse) => void;
};

export function ItemsList({ items, characters, selectedItemId, onEditItem }: ItemsListProps) {
  if (items.length === 0) {
    return (
      <EmptyState
        title="Nenhum item ainda"
        description="Crie o primeiro item deste livro para organizar objetos, pistas e artefatos da narrativa."
      />
    );
  }

  const characterNameById = new Map(characters.map((character) => [character.id, character.name]));

  return (
    <div className="grid max-h-[calc(100vh-210px)] content-start gap-2 overflow-y-auto pr-1">
      {items.map((item) => {
        const ownerName = item.currentOwnerCharacterId
          ? characterNameById.get(item.currentOwnerCharacterId) ?? "Dono não encontrado"
          : null;

        return (
          <article
            key={item.id}
            className={`grid min-h-[132px] content-start gap-2 rounded-md border bg-white p-3 shadow-sm transition ${
              selectedItemId === item.id ? "border-zinc-900 ring-2 ring-zinc-200" : "border-zinc-200 hover:border-zinc-300"
            }`}
          >
            <div className="grid grid-cols-[minmax(0,1fr)_auto] items-start gap-2">
              <button type="button" className="min-w-0 text-left" onClick={() => onEditItem(item)}>
                <h3 className="truncate text-sm font-semibold text-zinc-950">{item.name}</h3>
                <div className="mt-1 flex min-h-5 flex-wrap items-center gap-1.5 text-xs text-zinc-500">
                  {item.type ? <span className="max-w-full truncate">{item.type}</span> : null}
                  {item.type && ownerName ? <span aria-hidden="true">·</span> : null}
                  {ownerName ? <span className="max-w-full truncate">{ownerName}</span> : null}
                </div>
              </button>

              <button
                type="button"
                className="min-h-7 rounded-md px-2 text-xs font-medium text-zinc-600 transition hover:bg-zinc-100 hover:text-zinc-950"
                onClick={() => onEditItem(item)}
              >
                Editar
              </button>
            </div>

            <SummaryLine label="Importância" value={item.narrativeImportance} />
            <SummaryLine label="Descrição" value={item.description} />
          </article>
        );
      })}
    </div>
  );
}

function SummaryLine({ label, value }: { label: string; value: string | null }) {
  return (
    <p className="line-clamp-2 min-h-10 text-xs leading-5 text-zinc-600">
      {value ? (
        <>
          <span className="font-medium text-zinc-800">{label}:</span> {value}
        </>
      ) : (
        <span className="text-zinc-400">{label}: não informado</span>
      )}
    </p>
  );
}
