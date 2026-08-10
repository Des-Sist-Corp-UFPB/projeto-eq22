"use client";

import { useEffect } from "react";
import { usePathname } from "next/navigation";
import { ensureUmamiScript, trackPageView } from "@/lib/analytics/analytics";

export function UmamiAnalytics() {
  const pathname = usePathname();

  useEffect(() => {
    ensureUmamiScript();
  }, []);

  useEffect(() => {
    if (pathname) {
      trackPageView(pathname);
    }
  }, [pathname]);

  return null;
}
