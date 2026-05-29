import type { BookDashboardResponse } from "@/features/dashboard/types";
import { apiRequest } from "@/lib/api/client";

export type WritingProgressPeriod = "7d" | "15d" | "30d" | "3m" | "6m" | "12m";

export function getBookDashboard(bookId: string, progressPeriod: WritingProgressPeriod = "7d") {
  const searchParams = progressPeriod === "7d" ? "" : `?progressPeriod=${encodeURIComponent(progressPeriod)}`;
  return apiRequest<BookDashboardResponse>(`/api/books/${bookId}/dashboard${searchParams}`);
}
