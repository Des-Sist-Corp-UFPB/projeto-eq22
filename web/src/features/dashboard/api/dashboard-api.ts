import type {
  BookContributionDashboardResponse,
  BookDashboardResponse,
  UserDashboardResponse,
} from "@/features/dashboard/types";
import { apiRequest } from "@/lib/api/client";

export type WritingProgressPeriod = "7d" | "15d" | "30d" | "3m" | "6m" | "12m";

export function getBookDashboard(bookId: string, progressPeriod: WritingProgressPeriod = "7d") {
  const searchParams = progressPeriod === "7d" ? "" : `?progressPeriod=${encodeURIComponent(progressPeriod)}`;
  return apiRequest<BookDashboardResponse>(`/api/books/${bookId}/dashboard${searchParams}`);
}

export function getCurrentUserDashboard(progressPeriod: WritingProgressPeriod = "7d") {
  const searchParams = progressPeriod === "7d" ? "" : `?progressPeriod=${encodeURIComponent(progressPeriod)}`;
  return apiRequest<UserDashboardResponse>(`/api/dashboard/me${searchParams}`);
}

export function getBookContributions(
  bookId: string,
  progressPeriod: WritingProgressPeriod = "7d",
  contributorId?: string,
) {
  const searchParams = new URLSearchParams();
  if (progressPeriod !== "7d") {
    searchParams.set("progressPeriod", progressPeriod);
  }
  if (contributorId) {
    searchParams.set("contributorId", contributorId);
  }
  const queryString = searchParams.toString();
  return apiRequest<BookContributionDashboardResponse>(
    `/api/books/${bookId}/dashboard/contributions${queryString ? `?${queryString}` : ""}`,
  );
}
