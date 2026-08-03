"use client";

import { hashKey, MutationCache, QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { SESSION_QUERY_KEY } from "@/features/auth/session";
import { ApiError } from "@/lib/api/client";

export function QueryProvider({ children }: { children: React.ReactNode }) {
  const [queryClient] = useState(() => {
    // The handler needs the client the caches belong to, and the caches are built before it
    // exists, so it reaches the client through a holder filled in below.
    const created: { client?: QueryClient } = {};

    // A session can expire or be revoked while the app is open. Whichever request notices it first,
    // the answer is the same: the session is gone, and every cache entry fetched under it is the
    // previous tenant's data. Dropping everything except the session key itself (never enumerated
    // by name, so no domain query can be missed) and overwriting that one key with null — rather
    // than removing it — lets the route guard redirect on this same render pass instead of first
    // refetching /api/auth/me and racing a second 401.
    const onError = (error: unknown) => {
      if (error instanceof ApiError && error.status === 401) {
        const client = created.client;
        if (!client) return;
        client.removeQueries({
          predicate: (query) => hashKey(query.queryKey) !== hashKey(SESSION_QUERY_KEY),
        });
        client.getMutationCache().clear();
        client.setQueryData(SESSION_QUERY_KEY, null);
      }
    };

    const client = new QueryClient({
      queryCache: new QueryCache({ onError }),
      mutationCache: new MutationCache({ onError }),
      defaultOptions: {
        queries: {
          staleTime: 10_000,
          refetchOnWindowFocus: false,
        },
      },
    });

    created.client = client;
    return client;
  });

  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
