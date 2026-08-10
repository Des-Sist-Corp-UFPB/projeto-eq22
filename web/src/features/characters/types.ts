export type CharacterResponse = {
  id: string;
  bookId: string;
  name: string;
  nickname: string | null;
  age: number | null;
  sex: string | null;
  narrativeFunction: string | null;
  goal: string | null;
  conflict: string | null;
  arc: string | null;
  physicalDescription: string | null;
  personality: string | null;
  biography: string | null;
  notes: string | null;
  createdAt: string;
  updatedAt: string;
};

export type CharacterRequest = {
  name: string;
  nickname?: string | null;
  age?: number | null;
  sex?: string | null;
  narrativeFunction?: string | null;
  goal?: string | null;
  conflict?: string | null;
  arc?: string | null;
  physicalDescription?: string | null;
  personality?: string | null;
  biography?: string | null;
  notes?: string | null;
};

export type CharacterUpdateRequest = Partial<CharacterRequest>;
