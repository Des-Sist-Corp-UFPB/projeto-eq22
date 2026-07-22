import { apiRequest } from "@/lib/api/client";
import type { CharacterRequest, CharacterResponse, CharacterUpdateRequest } from "@/features/characters/types";

export function listCharacters(bookId: string) {
  return apiRequest<CharacterResponse[]>(`/api/books/${bookId}/characters`);
}

export function getCharacter(characterId: string) {
  return apiRequest<CharacterResponse>(`/api/characters/${characterId}`);
}

export function createCharacter(bookId: string, payload: CharacterRequest) {
  return apiRequest<CharacterResponse>(`/api/books/${bookId}/characters`, {
    method: "POST",
    body: payload,
  });
}

export function updateCharacter(characterId: string, payload: CharacterUpdateRequest) {
  return apiRequest<CharacterResponse>(`/api/characters/${characterId}`, {
    method: "PATCH",
    body: payload,
  });
}

export function deleteCharacter(characterId: string) {
  return apiRequest<void>(`/api/characters/${characterId}`, {
    method: "DELETE",
  });
}
