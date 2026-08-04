"use client";

import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { isStaleMutation, purgeAuthenticatedCaches, stampMutationGeneration } from "@/features/auth/session-cache";
import { SESSION_QUERY_KEY } from "@/features/auth/session-query-key";
import { ApiError } from "@/lib/api/client";

export function QueryProvider({ children }: { children: React.ReactNode }) {
  const [queryClient] = useState(() => {
    // The handler needs the client the caches belong to, and the caches are built before it
    // exists, so it reaches the client through a holder filled in below.
    const created: { client?: QueryClient } = {};

    // A session can expire or be revoked while the app is open. Whichever request notices it first,
    // the answer is the same: the session is gone, and every cache entry fetched under it is the
    // previous tenant's data. purgeAuthenticatedCaches leaves the session key itself alone —
    // overwriting it with null here, rather than removing it, lets the route guard redirect on this
    // same render pass instead of first refetching /api/auth/me and racing a second 401.
    const onError = (error: unknown) => {
      if (error instanceof ApiError && error.status === 401) {
        const client = created.client;
        if (!client) return;
        purgeAuthenticatedCaches(client);
        client.setQueryData(SESSION_QUERY_KEY, null);
      }
    };

    const client = new QueryClient({
      queryCache: new QueryCache({ onError }),
      mutationCache: new MutationCache({
        onError,
        // Stamps the reconciliation generation (session-cache.ts) current at the moment this
        // mutation started, before its mutationFn runs — the only point at which "which identity did
        // this mutation start under" can still be answered.
        onMutate: (_variables, mutation) => {
          const client = created.client;
          if (client) stampMutationGeneration(client, mutation);
        },
        // A cross-tab or focus reconciliation (session-sync.ts) can land while this exact mutation
        // was already in flight under the identity that reconciliation just discarded. Its own
        // onSuccess already ran and may have written stale data by the time this fires — this purges
        // it right back out. Ordinary mutations are never affected: isStaleMutation is false unless a
        // reconciliation actually happened after this mutation started.
        onSettled: (_data, _error, _variables, _context, mutation) => {
          const client = created.client;
          if (client && isStaleMutation(client, mutation)) {
            purgeAuthenticatedCaches(client);
          }
        },
      }),
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
