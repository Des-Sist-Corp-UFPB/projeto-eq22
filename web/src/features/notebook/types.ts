export type NotebookCategory = {
  id: string;
  bookId: string;
  name: string;
  sortOrder: number;
  isDefault: boolean;
  createdAt: string;
  updatedAt: string;
};

export type NotebookNote = {
  id: string;
  bookId: string;
  categoryId: string | null;
  category: NotebookCategory | null;
  title: string;
  content: string | null;
  status: NotebookNoteStatus;
  createdAt: string;
  updatedAt: string;
};

export type NotebookNoteStatus = "OPEN" | "RESOLVED";

export type NotebookNoteRequest = {
  title: string;
  content?: string | null;
  categoryId?: string | null;
  status?: NotebookNoteStatus | null;
};

export type NotebookNoteUpdateRequest = Partial<NotebookNoteRequest>;

export type NotebookCategoryRequest = {
  name: string;
  sortOrder?: number | null;
};

export type NotebookCategoryUpdateRequest = Partial<NotebookCategoryRequest>;
