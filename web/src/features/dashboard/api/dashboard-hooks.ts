"use client";

import { useQuery } from "@tanstack/react-query";
import { getBookDashboard } from "@/features/dashboard/api/dashboard-api";
import { queryKeys } from "@/lib/query/keys";

export function useBookDashboard(bookId: string) {
  return useQuery({
    queryKey: queryKeys.bookDashboard(bookId),
    queryFn: () => getBookDashboard(bookId),
    enabled: Boolean(bookId),
  });
}
