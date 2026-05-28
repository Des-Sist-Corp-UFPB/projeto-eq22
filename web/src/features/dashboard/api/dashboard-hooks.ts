"use client";

import { useQuery } from "@tanstack/react-query";
import { getBookDashboard, type WritingProgressPeriod } from "@/features/dashboard/api/dashboard-api";
import { queryKeys } from "@/lib/query/keys";

export function useBookDashboard(bookId: string, progressPeriod: WritingProgressPeriod = "7d") {
  return useQuery({
    queryKey: [...queryKeys.bookDashboard(bookId), progressPeriod],
    queryFn: () => getBookDashboard(bookId, progressPeriod),
    enabled: Boolean(bookId),
  });
}
