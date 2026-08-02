"use client";

import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, type ReactNode } from "react";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { SessionBar } from "@/features/auth/components/session-bar";
import { BACKEND_UNAVAILABLE_MESSAGE } from "@/features/auth/components/login-form";
import { useSession } from "@/features/auth/session";

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
  const { data: session, isPending, isError } = useSession();
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

  if (isError) {
    return <GuardStatus variant="error">{BACKEND_UNAVAILABLE_MESSAGE}</GuardStatus>;
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

function GuardStatus({ variant, children }: { variant: "info" | "error"; children: ReactNode }) {
  return (
    <main className="grid min-h-screen place-items-center bg-[#f7f7f2] px-5">
      <FeedbackMessage variant={variant}>{children}</FeedbackMessage>
    </main>
  );
}
