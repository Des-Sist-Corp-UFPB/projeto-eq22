import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import type { CharacterResponse } from "@/features/characters/types";

type CharactersListProps = {
  characters: CharacterResponse[];
  selectedCharacterId: string | null;
  deletePendingCharacterId: string | null;
  onEditCharacter: (character: CharacterResponse) => void;
  onDeleteCharacter: (character: CharacterResponse) => void;
};

export function CharactersList({
  characters,
  selectedCharacterId,
  deletePendingCharacterId,
  onEditCharacter,
  onDeleteCharacter,
}: CharactersListProps) {
  if (characters.length === 0) {
    return (
      <EmptyState
        title="Nenhum personagem ainda"
        description="Crie o primeiro personagem deste livro para começar a organizar o elenco da narrativa."
      />
    );
  }

  return (
    <div className="grid max-h-[calc(100vh-210px)] content-start gap-2 overflow-y-auto pr-1">
      {characters.map((character) => (
        <article
          key={character.id}
          className={`grid min-h-[132px] content-start gap-2 rounded-md border bg-white p-3 shadow-sm transition ${
            selectedCharacterId === character.id
              ? "border-zinc-900 ring-2 ring-zinc-200"
              : "border-zinc-200 hover:border-zinc-300"
          }`}
        >
          <div className="grid grid-cols-[minmax(0,1fr)_auto] items-start gap-2">
            <button type="button" className="min-w-0 text-left" onClick={() => onEditCharacter(character)}>
              <h3 className="truncate text-sm font-semibold text-zinc-950">{character.name}</h3>
              <div className="mt-1 flex min-h-5 flex-wrap items-center gap-1.5 text-xs text-zinc-500">
                {character.nickname ? <span className="max-w-full truncate">{character.nickname}</span> : null}
                {character.nickname && character.age !== null ? <span aria-hidden="true">·</span> : null}
                {character.age !== null ? <span>{character.age} anos</span> : null}
              </div>
            </button>

            <div className="flex shrink-0 items-center gap-1">
              <Button
                type="button"
                variant="ghost"
                size="sm"
                className="min-h-7 px-2 text-xs"
                onClick={() => onEditCharacter(character)}
              >
                Editar
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                className="min-h-7 px-2 text-xs text-red-700 hover:bg-red-50"
                disabled={deletePendingCharacterId === character.id}
                onClick={() => onDeleteCharacter(character)}
              >
                {deletePendingCharacterId === character.id ? "..." : "Excluir"}
              </Button>
            </div>
          </div>

          {character.narrativeFunction ? (
            <p className="truncate text-xs font-medium text-zinc-700">{character.narrativeFunction}</p>
          ) : (
            <div className="h-4" aria-hidden="true" />
          )}

          <div className="grid gap-1 text-xs leading-5 text-zinc-600">
            <SummaryLine label="Objetivo" value={character.goal} />
            <SummaryLine label="Conflito" value={character.conflict} />
          </div>
        </article>
      ))}
    </div>
  );
}

function SummaryLine({ label, value }: { label: string; value: string | null }) {
  return (
    <p className="line-clamp-2 min-h-10">
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
