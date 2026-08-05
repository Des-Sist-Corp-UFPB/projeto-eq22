"use client";

import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { SessionBar } from "@/features/auth/components/session-bar";
import { BACKEND_UNAVAILABLE_MESSAGE } from "@/features/auth/components/login-form";
import { useSession } from "@/features/auth/session";
import { useSessionReconciliation } from "@/features/auth/session-sync";

const LOGIN_ROUTE = "/login";
const LIBRARY_ROUTE = "/library";

/**
 * Convenience, not authorization. The backend refuses every unauthenticated request on its own;
 * this only spares the reader a screen full of failures and sends them where they can act. It
 * wraps the whole app so a new route is protected by existing rather than by remembering to.
 */
export function SessionGuard({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const { data: session, isPending, isError, isFetching, errorUpdateCount, refetch } = useSession();
  // Mounted unconditionally (not just on protected routes) so a login in another tab is still
  // picked up while this tab happens to be sitting on /login.
  const isReconciling = useSessionReconciliation();
  const isLoginRoute = pathname === LOGIN_ROUTE;
  const hadSession = useRef(false);

  useEffect(() => {
    if (isPending || isError) {
      return;
    }

    if (session) {
      hadSession.current = true;
      if (isLoginRoute) {
        router.replace(LIBRARY_ROUTE);
      }
      return;
    }

    if (!isLoginRoute) {
      // Having held a session in this session of the app means it ended rather than never existed,
      // which is the difference between "your session expired" and a plain login screen.
      router.replace(hadSession.current ? `${LOGIN_ROUTE}?reason=expired` : LOGIN_ROUTE);
    }
  }, [isPending, isError, session, isLoginRoute, router]);

  // The login screen renders whatever the session says: it is the destination of every redirect,
  // and gating it on a resolved session would leave a logged-out reader looking at a spinner.
  if (isLoginRoute) {
    return <>{children}</>;
  }

  // Another tab's login/logout, or this tab regaining focus, means the cookie underneath this app
  // may no longer name the identity the cache was built for. Nothing protected renders — not even
  // stale data already in the cache — until /api/auth/me has answered again.
  if (isReconciling) {
    return <GuardStatus variant="info">Verificando sessão…</GuardStatus>;
  }

  // A network blip or a 5xx leaves this query in a terminal error state: it never retries on its
  // own and never refetches on focus (both deliberate, so nobody is logged out at random), and this
  // guard stays mounted across navigation — so without an explicit way to ask again, a reader whose
  // backend came back sees the outage screen until they reload by hand. One button, one request per
  // press, no automatic loop. React Query clears the error and reports `pending` again while a
  // query with no data refetches, so `isError` alone would replace this screen — button included —
  // with a spinner the moment the retry starts.
  const isRetryingAfterFailure = isPending && isFetching && errorUpdateCount > 0;

  if (isError || isRetryingAfterFailure) {
    return (
      <GuardStatus
        variant="error"
        action={
          <Button type="button" variant="secondary" disabled={isFetching} onClick={() => refetch()}>
            {isFetching ? "Tentando…" : "Tentar novamente"}
          </Button>
        }
      >
        {BACKEND_UNAVAILABLE_MESSAGE}
      </GuardStatus>
    );
  }

  if (isPending || !session) {
    return <GuardStatus variant="info">Carregando…</GuardStatus>;
  }

  return (
    <>
      <SessionBar session={session} />
      {children}
    </>
  );
}

/** `action` sits outside the announced message so the alert reads as the status, not as a control. */
function GuardStatus({
  variant,
  action,
  children,
}: {
  variant: "info" | "error";
  action?: ReactNode;
  children: ReactNode;
}) {
  return (
    <main className="grid min-h-screen place-items-center bg-[#f7f7f2] px-5">
      <div className="grid justify-items-center gap-3">
        <FeedbackMessage variant={variant}>{children}</FeedbackMessage>
        {action}
      </div>
    </main>
  );
}
