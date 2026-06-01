export type BookStatus = "PLANNING" | "WRITING" | "REVISING" | "FINISHED" | "ARCHIVED";
export type DayOfWeek = "MONDAY" | "TUESDAY" | "WEDNESDAY" | "THURSDAY" | "FRIDAY" | "SATURDAY" | "SUNDAY";

export type Book = {
  id: string;
  title: string;
  subtitle: string | null;
  description: string | null;
  status: BookStatus;
  targetWordCount: number | null;
  dailyTargetWordCount: number | null;
  plannedWritingDays: DayOfWeek[];
  createdAt: string;
  updatedAt: string;
};

export type CreateBookRequest = {
  title: string;
  subtitle?: string;
  description?: string;
  targetWordCount?: number | null;
  dailyTargetWordCount?: number | null;
  plannedWritingDays?: DayOfWeek[];
};

export type UpdateBookRequest = {
  title?: string;
  subtitle?: string;
  description?: string;
  status?: BookStatus;
  targetWordCount?: number | null;
  dailyTargetWordCount?: number | null;
  plannedWritingDays?: DayOfWeek[];
};
