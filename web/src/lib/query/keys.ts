export const queryKeys = {
  books: ["books"] as const,
  book: (bookId: string) => ["books", bookId] as const,
  characters: (bookId: string) => ["books", bookId, "characters"] as const,
  character: (characterId: string) => ["characters", characterId] as const,
  outline: (bookId: string) => ["books", bookId, "outline"] as const,
  scene: (sceneId: string) => ["scenes", sceneId] as const,
};
