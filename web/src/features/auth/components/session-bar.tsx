"use client";

import { Button } from "@/components/ui/button";
import type { AuthenticatedSession } from "@/features/auth/api/auth-api";
import { useLogout } from "@/features/auth/session";

/**
 * Shows who the server decided you are. The name and workspace come from the session payload, so
 * what is on screen is the same identity the backend uses to scope every query.
 */
export function SessionBar({ session }: { session: AuthenticatedSession }) {
  const logout = useLogout();

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-b border-zinc-200 bg-white px-5 py-2 md:px-8">
      <p className="text-sm text-zinc-600">
        <span className="font-medium text-zinc-900">{session.user.displayName}</span>
        <span className="px-2 text-zinc-300">/</span>
        <span>{session.activeWorkspace.name}</span>
      </p>
      <Button
        variant="secondary"
        size="sm"
        onClick={() => logout.mutate()}
        disabled={logout.isPending}
      >
        {logout.isPending ? "Saindo…" : "Sair"}
      </Button>
    </div>
  );
}
