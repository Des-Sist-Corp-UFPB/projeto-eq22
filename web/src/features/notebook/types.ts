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
  createdAt: string;
  updatedAt: string;
};

export type NotebookNoteRequest = {
  title: string;
  content?: string | null;
  categoryId?: string | null;
};

export type NotebookNoteUpdateRequest = Partial<NotebookNoteRequest>;

export type NotebookCategoryRequest = {
  name: string;
  sortOrder?: number | null;
};

export type NotebookCategoryUpdateRequest = Partial<NotebookCategoryRequest>;
