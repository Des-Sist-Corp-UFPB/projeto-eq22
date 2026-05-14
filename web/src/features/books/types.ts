export type BookStatus = "PLANNING" | "WRITING" | "REVISING" | "FINISHED" | "ARCHIVED";

export type Book = {
  id: string;
  title: string;
  subtitle: string | null;
  description: string | null;
  status: BookStatus;
  targetWordCount: number | null;
  createdAt: string;
  updatedAt: string;
};

export type CreateBookRequest = {
  title: string;
  subtitle?: string;
  description?: string;
  targetWordCount?: number | null;
};

export type UpdateBookRequest = {
  title?: string;
  subtitle?: string;
  description?: string;
  status?: BookStatus;
  targetWordCount?: number | null;
};
