"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { fetchSession, login, logout } from "@/features/auth/api/auth-api";
import { announceSessionChanged } from "@/features/auth/session-sync";
import { SESSION_QUERY_KEY } from "@/features/auth/session-query-key";

export { SESSION_QUERY_KEY };

export function useSession() {
  return useQuery({
    queryKey: SESSION_QUERY_KEY,
    queryFn: fetchSession,
    // Refetching on an interval would just be a way to log the user out at random. Focus and
    // cross-tab revalidation still happen, deliberately, but through useSessionReconciliation
    // (session-sync.ts) rather than this query's own options — that path can tell "checked, nothing
    // changed" from "checked, identity changed" and only the latter touches the rest of the cache.
    staleTime: Infinity,
    retry: false,
  });
}

export function useLogin() {
  const queryClient = useQueryClient();
  const router = useRouter();

  return useMutation({
    mutationFn: ({ email, password }: { email: string; password: string }) => login(email, password),
    onSuccess: (session) => {
      queryClient.setQueryData(SESSION_QUERY_KEY, session);
      announceSessionChanged();
      router.replace("/library");
    },
  });
}

export function useLogout() {
  const queryClient = useQueryClient();
  const router = useRouter();

  return useMutation({
    mutationFn: logout,
    onSuccess: () => {
      // Everything cached was fetched as this user in this tenant. Dropping the whole cache is the
      // only version of "clear authenticated data" that cannot miss a key.
      queryClient.clear();
      announceSessionChanged();
      router.replace("/login");
    },
  });
}
