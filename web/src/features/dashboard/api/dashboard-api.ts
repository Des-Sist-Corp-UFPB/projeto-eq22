import type { BookDashboardResponse } from "@/features/dashboard/types";
import { apiRequest } from "@/lib/api/client";

export function getBookDashboard(bookId: string) {
  return apiRequest<BookDashboardResponse>(`/api/books/${bookId}/dashboard`);
}
