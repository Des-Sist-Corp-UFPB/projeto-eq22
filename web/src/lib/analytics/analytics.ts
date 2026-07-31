type UmamiTrackPayload = Record<string, unknown>;

type UmamiGlobal = {
  track: (payload?: string | UmamiTrackPayload, eventData?: Record<string, string>) => void;
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

/**
 * Todo envio usa URL sanitizada explícita (nunca a captura automática da URL
 * atual): sem query string, sem hash e com segmentos que parecem IDs (UUID,
 * numérico, hex ou token opaco longo) normalizados para `{id}` — ex.:
 * `/books/{id}`. Referrer segue a mesma regra (externo vira só a origem) e o
 * título nunca é enviado (pode conter título de manuscrito).
 */
const UUID_SEGMENT = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const NUMERIC_SEGMENT = /^\d+$/;
const HEX_SEGMENT = /^[0-9a-f]{16,}$/i;
const LONG_OPAQUE_SEGMENT = /^[\w-]{21,}$/;

type TrackedItem = {
  url: string;
  name?: AnalyticsEvent["name"];
  data?: Record<string, string>;
};

/**
 * Fila pequena para page views e eventos ocorridos antes do script carregar:
 * preserva navegações distintas, deduplica itens consecutivos idênticos e,
 * cheia, descarta o item mais antigo (as navegações recentes valem mais).
 */
const MAX_PENDING_ITEMS = 10;
let pendingItems: TrackedItem[] = [];
let lastTrackedPath: string | null = null;

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
    script.addEventListener("load", flushPendingItems);
    document.head.appendChild(script);
  } catch {
    // Analytics nunca bloqueia o produto.
  }
}

export function sanitizeTrackedPath(rawPath: string): string {
  const path = rawPath.split(/[?#]/)[0];
  const sanitized = path
    .split("/")
    .map((segment) => (isOpaqueSegment(segment) ? "{id}" : segment))
    .join("/");
  return sanitized.startsWith("/") ? sanitized : `/${sanitized}`;
}

function isOpaqueSegment(segment: string): boolean {
  return (
    UUID_SEGMENT.test(segment) ||
    NUMERIC_SEGMENT.test(segment) ||
    HEX_SEGMENT.test(segment) ||
    LONG_OPAQUE_SEGMENT.test(segment)
  );
}

function sanitizeReferrer(referrer: string): string {
  if (!referrer || typeof window === "undefined") {
    return "";
  }
  try {
    const url = new URL(referrer);
    if (url.origin === window.location.origin) {
      return url.origin + sanitizeTrackedPath(url.pathname);
    }
    return url.origin;
  } catch {
    return "";
  }
}

export function trackPageView(path: string): void {
  try {
    if (!getUmamiConfig() || !path || path === lastTrackedPath) {
      return;
    }

    lastTrackedPath = path;
    dispatch({ url: sanitizeTrackedPath(path) });
  } catch {
    // Analytics nunca bloqueia o produto.
  }
}

export function trackEvent(event: AnalyticsEvent): void {
  try {
    if (!getUmamiConfig() || typeof window === "undefined") {
      return;
    }

    const allowedProperties = ALLOWED_EVENTS[event.name];
    if (!allowedProperties) {
      return;
    }

    const data = sanitizeEventData(allowedProperties, "data" in event ? event.data : undefined);
    dispatch({
      url: sanitizeTrackedPath(window.location.pathname),
      name: event.name,
      ...(data && Object.keys(data).length > 0 ? { data } : {}),
    });
  } catch {
    // Analytics nunca bloqueia o produto.
  }
}

function dispatch(item: TrackedItem): void {
  const umami = typeof window === "undefined" ? undefined : window.umami;
  if (umami) {
    sendToTracker(umami, item);
    return;
  }

  const last = pendingItems[pendingItems.length - 1];
  if (last && JSON.stringify(last) === JSON.stringify(item)) {
    return;
  }
  pendingItems.push(item);
  if (pendingItems.length > MAX_PENDING_ITEMS) {
    pendingItems.shift();
  }
}

function sendToTracker(umami: UmamiGlobal, item: TrackedItem): void {
  umami.track({
    url: item.url,
    referrer: sanitizeReferrer(document.referrer),
    title: "",
    ...(item.name ? { name: item.name } : {}),
    ...(item.data ? { data: item.data } : {}),
  });
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

function flushPendingItems(): void {
  try {
    const umami = typeof window === "undefined" ? undefined : window.umami;
    if (!umami) {
      return;
    }

    const items = pendingItems;
    pendingItems = [];
    items.forEach((item) => sendToTracker(umami, item));
  } catch {
    // Analytics nunca bloqueia o produto.
  }
}

export function __resetAnalyticsForTest(): void {
  lastTrackedPath = null;
  pendingItems = [];
}
