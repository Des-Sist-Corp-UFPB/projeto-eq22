"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { fetchSession, login, logout } from "@/features/auth/api/auth-api";

/** One cache entry for the whole app, so /api/auth/me is requested once however many components ask. */
export const SESSION_QUERY_KEY = ["auth", "session"] as const;

export function useSession() {
  return useQuery({
    queryKey: SESSION_QUERY_KEY,
    queryFn: fetchSession,
    // The session only changes through login and logout, both of which write this entry directly.
    // Refetching on an interval or on focus would just be a way to log the user out at random.
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
      router.replace("/login");
    },
  });
}
