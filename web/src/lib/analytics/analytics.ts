type UmamiGlobal = {
  track: (eventName?: string, eventData?: Record<string, string>) => void;
};

declare global {
  interface Window {
    umami?: UmamiGlobal;
  }
}

export type UmamiConfig = {
  scriptUrl: string;
  websiteId: string;
  hostUrl?: string;
};

export type SceneSaveSource = "AUTO_SAVE" | "MANUAL_SAVE";
export type SceneAnalysisFailureCategory = "unavailable" | "request_failed";
export type ExportTarget = "manuscript" | "notebook";
export type ExportFormatProperty = "txt" | "md" | "docx";

export type AnalyticsEvent =
  | { name: "book_created" }
  | { name: "scene_saved"; data: { source: SceneSaveSource } }
  | { name: "scene_analysis_requested" }
  | { name: "scene_analysis_succeeded" }
  | { name: "scene_analysis_failed"; data: { category: SceneAnalysisFailureCategory } }
  | { name: "book_exported"; data: { target: ExportTarget; format: ExportFormatProperty } };

/**
 * Allowlist de eventos, propriedades e valores. Nada fora desta tabela chega ao
 * Umami: eventos desconhecidos são descartados e propriedades ou valores não
 * enumerados são removidos antes do envio. Nunca inclua conteúdo de manuscrito,
 * títulos, emails, nomes ou IDs brutos aqui.
 */
const ALLOWED_EVENTS: Record<AnalyticsEvent["name"], Record<string, readonly string[]>> = {
  book_created: {},
  scene_saved: { source: ["AUTO_SAVE", "MANUAL_SAVE"] },
  scene_analysis_requested: {},
  scene_analysis_succeeded: {},
  scene_analysis_failed: { category: ["unavailable", "request_failed"] },
  book_exported: { target: ["manuscript", "notebook"], format: ["txt", "md", "docx"] },
};

const SCRIPT_MARKER_ATTRIBUTE = "data-iwrite-umami";

let lastTrackedPath: string | null = null;
let hasPendingPageView = false;

export function getUmamiConfig(): UmamiConfig | null {
  const enabled = process.env.NEXT_PUBLIC_UMAMI_ENABLED === "true";
  const scriptUrl = process.env.NEXT_PUBLIC_UMAMI_SCRIPT_URL ?? "";
  const websiteId = process.env.NEXT_PUBLIC_UMAMI_WEBSITE_ID ?? "";
  const hostUrl = process.env.NEXT_PUBLIC_UMAMI_HOST_URL ?? "";

  if (!enabled || !scriptUrl.trim() || !websiteId.trim()) {
    return null;
  }

  return {
    scriptUrl: scriptUrl.trim(),
    websiteId: websiteId.trim(),
    ...(hostUrl.trim() ? { hostUrl: hostUrl.trim() } : {}),
  };
}

export function ensureUmamiScript(): void {
  try {
    const config = getUmamiConfig();
    if (!config || typeof document === "undefined") {
      return;
    }

    if (document.querySelector(`script[${SCRIPT_MARKER_ATTRIBUTE}]`)) {
      return;
    }

    const script = document.createElement("script");
    script.src = config.scriptUrl;
    script.defer = true;
    script.setAttribute(SCRIPT_MARKER_ATTRIBUTE, "true");
    script.setAttribute("data-website-id", config.websiteId);
    script.setAttribute("data-auto-track", "false");
    if (config.hostUrl) {
      script.setAttribute("data-host-url", config.hostUrl);
    }
    script.addEventListener("load", flushPendingPageView);
    document.head.appendChild(script);
  } catch {
    // Analytics nunca bloqueia o produto.
  }
}

export function trackPageView(path: string): void {
  try {
    if (!getUmamiConfig() || !path || path === lastTrackedPath) {
      return;
    }

    lastTrackedPath = path;

    const umami = typeof window === "undefined" ? undefined : window.umami;
    if (!umami) {
      hasPendingPageView = true;
      return;
    }

    hasPendingPageView = false;
    umami.track();
  } catch {
    // Analytics nunca bloqueia o produto.
  }
}

export function trackEvent(event: AnalyticsEvent): void {
  try {
    if (!getUmamiConfig()) {
      return;
    }

    const umami = typeof window === "undefined" ? undefined : window.umami;
    const allowedProperties = ALLOWED_EVENTS[event.name];
    if (!umami || !allowedProperties) {
      return;
    }

    const data = sanitizeEventData(allowedProperties, "data" in event ? event.data : undefined);
    if (data && Object.keys(data).length > 0) {
      umami.track(event.name, data);
    } else {
      umami.track(event.name);
    }
  } catch {
    // Analytics nunca bloqueia o produto.
  }
}

function sanitizeEventData(
  allowedProperties: Record<string, readonly string[]>,
  data: Record<string, string> | undefined
): Record<string, string> | null {
  if (!data) {
    return null;
  }

  const sanitized: Record<string, string> = {};
  for (const [property, value] of Object.entries(data)) {
    if (allowedProperties[property]?.includes(value)) {
      sanitized[property] = value;
    }
  }
  return sanitized;
}

function flushPendingPageView(): void {
  try {
    if (!hasPendingPageView) {
      return;
    }

    hasPendingPageView = false;
    window.umami?.track();
  } catch {
    // Analytics nunca bloqueia o produto.
  }
}

export function __resetAnalyticsForTest(): void {
  lastTrackedPath = null;
  hasPendingPageView = false;
}
