export type ScenePlanningRequest = {
  goal?: string | null;
  conflict?: string | null;
  outcome?: string | null;
  povCharacterId?: string | null;
  participantCharacterIds: string[];
  mainLocationId?: string | null;
  itemIds: string[];
};
