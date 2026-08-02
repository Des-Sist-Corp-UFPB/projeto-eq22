"use client";

import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { SESSION_QUERY_KEY } from "@/features/auth/session";
import { ApiError } from "@/lib/api/client";

export function QueryProvider({ children }: { children: React.ReactNode }) {
  const [queryClient] = useState(() => {
    let client!: QueryClient;

    // A session can expire or be revoked while the app is open. Whichever request notices it first,
    // the answer is the same: the session is gone. Recording it here lets the route guard redirect
    // once, instead of every screen inventing its own handling of a 401.
    const onError = (error: unknown) => {
      if (error instanceof ApiError && error.status === 401) {
        client.setQueryData(SESSION_QUERY_KEY, null);
      }
    };

    client = new QueryClient({
      queryCache: new QueryCache({ onError }),
      mutationCache: new MutationCache({ onError }),
      defaultOptions: {
        queries: {
          staleTime: 10_000,
          refetchOnWindowFocus: false,
        },
      },
    });

    return client;
  });

  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
