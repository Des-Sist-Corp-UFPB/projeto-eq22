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
    <div className="grid gap-3">
      {characters.map((character) => (
        <article
          key={character.id}
          className={`rounded-md border bg-white p-4 shadow-sm ${
            selectedCharacterId === character.id ? "border-zinc-900" : "border-zinc-200"
          }`}
        >
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="min-w-0">
              <h3 className="truncate text-base font-semibold text-zinc-950">{character.name}</h3>
              {character.nickname ? <p className="mt-1 text-sm text-zinc-500">{character.nickname}</p> : null}
            </div>

            <div className="flex shrink-0 items-center gap-2">
              <Button type="button" variant="secondary" size="sm" onClick={() => onEditCharacter(character)}>
                Editar
              </Button>
              <Button
                type="button"
                variant="secondary"
                size="sm"
                className="border-red-200 text-red-700 hover:bg-red-50"
                disabled={deletePendingCharacterId === character.id}
                onClick={() => onDeleteCharacter(character)}
              >
                {deletePendingCharacterId === character.id ? "Excluindo..." : "Excluir"}
              </Button>
            </div>
          </div>

          <div className="mt-3 grid gap-2 text-sm text-zinc-600">
            {character.narrativeFunction ? (
              <p>
                <span className="font-medium text-zinc-800">Função:</span> {character.narrativeFunction}
              </p>
            ) : null}
            {character.goal ? (
              <p>
                <span className="font-medium text-zinc-800">Objetivo:</span> {character.goal}
              </p>
            ) : null}
          </div>
        </article>
      ))}
    </div>
  );
}
