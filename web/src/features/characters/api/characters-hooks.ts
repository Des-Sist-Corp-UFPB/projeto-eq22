"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  createCharacter,
  deleteCharacter,
  getCharacter,
  listCharacters,
  updateCharacter,
} from "@/features/characters/api/characters-api";
import type { CharacterRequest, CharacterUpdateRequest } from "@/features/characters/types";
import { queryKeys } from "@/lib/query/keys";

type UpdateCharacterVariables = {
  characterId: string;
  payload: CharacterUpdateRequest;
};

export function useCharacters(bookId: string) {
  return useQuery({
    queryKey: queryKeys.characters(bookId),
    queryFn: () => listCharacters(bookId),
    enabled: Boolean(bookId),
  });
}

export function useCharacter(characterId: string | null) {
  return useQuery({
    queryKey: characterId ? queryKeys.character(characterId) : ["characters", "empty"],
    queryFn: () => getCharacter(characterId as string),
    enabled: Boolean(characterId),
  });
}

export function useCreateCharacter(bookId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (payload: CharacterRequest) => createCharacter(bookId, payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.characters(bookId) });
    },
  });
}

export function useUpdateCharacter(bookId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ characterId, payload }: UpdateCharacterVariables) => updateCharacter(characterId, payload),
    onSuccess: (character) => {
      void queryClient.setQueryData(queryKeys.character(character.id), character);
      void queryClient.invalidateQueries({ queryKey: queryKeys.characters(bookId) });
    },
  });
}

export function useDeleteCharacter(bookId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (characterId: string) => deleteCharacter(characterId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.characters(bookId) });
    },
  });
}
