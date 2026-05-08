"use client";

import { useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { CharacterForm } from "@/features/characters/components/character-form";
import { CharactersList } from "@/features/characters/components/characters-list";
import {
  useCharacters,
  useCreateCharacter,
  useUpdateCharacter,
} from "@/features/characters/api/characters-hooks";
import type { CharacterRequest, CharacterResponse } from "@/features/characters/types";
import { ApiError } from "@/lib/api/client";

type CharactersPanelProps = {
  bookId: string;
};

export function CharactersPanel({ bookId }: CharactersPanelProps) {
  const charactersQuery = useCharacters(bookId);
  const createMutation = useCreateCharacter(bookId);
  const updateMutation = useUpdateCharacter(bookId);
  const [selectedCharacter, setSelectedCharacter] = useState<CharacterResponse | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  const activeMutation = selectedCharacter ? updateMutation : createMutation;
  const errorMessage = useMemo(() => getCharacterErrorMessage(activeMutation.error), [activeMutation.error]);

  function startCreate() {
    setSelectedCharacter(null);
    setSuccessMessage(null);
    createMutation.reset();
    updateMutation.reset();
  }

  function startEdit(character: CharacterResponse) {
    setSelectedCharacter(character);
    setSuccessMessage(null);
    createMutation.reset();
    updateMutation.reset();
  }

  function handleSubmit(payload: CharacterRequest) {
    setSuccessMessage(null);

    if (selectedCharacter) {
      updateMutation.mutate(
        { characterId: selectedCharacter.id, payload },
        {
          onSuccess: (character) => {
            setSelectedCharacter(character);
            setSuccessMessage("Personagem atualizado com sucesso.");
          },
        },
      );
      return;
    }

    createMutation.mutate(payload, {
      onSuccess: (character) => {
        setSelectedCharacter(character);
        setSuccessMessage("Personagem criado com sucesso.");
      },
    });
  }

  return (
    <section className="h-full overflow-y-auto bg-zinc-50 p-4 md:p-6">
      <div className="mx-auto grid max-w-6xl gap-4">
        <header className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <p className="text-xs font-medium uppercase text-zinc-500">Planejamento</p>
            <h1 className="text-xl font-semibold text-zinc-950">Personagens</h1>
          </div>

          <Button type="button" variant="secondary" onClick={startCreate}>
            Novo personagem
          </Button>
        </header>

        {charactersQuery.isLoading ? <LoadingState label="Carregando personagens..." /> : null}

        {charactersQuery.isError ? (
          <ErrorState message="Não foi possível carregar os personagens. Verifique o backend e tente novamente." />
        ) : null}

        {charactersQuery.data ? (
          <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_420px]">
            <CharactersList
              characters={charactersQuery.data}
              selectedCharacterId={selectedCharacter?.id ?? null}
              onEditCharacter={startEdit}
            />

            <CharacterForm
              character={selectedCharacter}
              isPending={activeMutation.isPending}
              errorMessage={errorMessage}
              successMessage={successMessage}
              onCancelEdit={startCreate}
              onSubmit={handleSubmit}
            />
          </div>
        ) : null}
      </div>
    </section>
  );
}

function getCharacterErrorMessage(error: unknown) {
  if (!error) {
    return null;
  }

  if (error instanceof ApiError) {
    return error.message || "Não foi possível salvar o personagem agora.";
  }

  return "Não foi possível salvar o personagem. Verifique a API e tente novamente.";
}
