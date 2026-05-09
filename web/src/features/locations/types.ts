export type LocationResponse = {
  id: string;
  bookId: string;
  name: string;
  type: string | null;
  description: string | null;
  historyContext: string | null;
  narrativeImportance: string | null;
  notes: string | null;
  createdAt: string;
  updatedAt: string;
};

export type LocationRequest = {
  name: string;
  type?: string | null;
  description?: string | null;
  historyContext?: string | null;
  narrativeImportance?: string | null;
  notes?: string | null;
};

export type LocationUpdateRequest = Partial<LocationRequest>;
