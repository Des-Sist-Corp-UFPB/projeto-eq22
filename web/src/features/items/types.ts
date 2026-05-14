export type CharacterSummaryResponse = {
  id: string;
  name: string;
  nickname: string | null;
};

export type ItemResponse = {
  id: string;
  bookId: string;
  name: string;
  type: string | null;
  description: string | null;
  origin: string | null;
  currentOwnerCharacterId: string | null;
  currentOwnerCharacter: CharacterSummaryResponse | null;
  narrativeImportance: string | null;
  notes: string | null;
  createdAt: string;
  updatedAt: string;
};

export type ItemRequest = {
  name: string;
  type?: string | null;
  description?: string | null;
  origin?: string | null;
  currentOwnerCharacterId?: string | null;
  narrativeImportance?: string | null;
  notes?: string | null;
};

export type ItemUpdateRequest = Partial<ItemRequest>;
