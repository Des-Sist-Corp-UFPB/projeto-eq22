import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import {
  __resetAnalyticsForTest,
  ensureUmamiScript,
  getUmamiConfig,
  sanitizeTrackedPath,
  trackEvent,
  trackPageView,
  type AnalyticsEvent,
} from "@/lib/analytics/analytics";

const SCRIPT_SELECTOR = "script[data-iwrite-umami]";
const UUID = "123e4567-e89b-12d3-a456-426614174000";

function stubValidConfig() {
  vi.stubEnv("NEXT_PUBLIC_UMAMI_ENABLED", "true");
  vi.stubEnv("NEXT_PUBLIC_UMAMI_SCRIPT_URL", "https://umami.example.com/script.js");
  vi.stubEnv("NEXT_PUBLIC_UMAMI_WEBSITE_ID", "11111111-1111-1111-1111-111111111111");
}

function installUmami() {
  const track = vi.fn();
  window.umami = { track };
  return track;
}

function stubReferrer(value: string) {
  Object.defineProperty(document, "referrer", { value, configurable: true });
}

function trackedUrls(track: ReturnType<typeof vi.fn>) {
  return track.mock.calls.map(([payload]) => (payload as { url: string }).url);
}

describe("analytics", () => {
  beforeEach(() => {
    __resetAnalyticsForTest();
    document.querySelectorAll(SCRIPT_SELECTOR).forEach((script) => script.remove());
    delete window.umami;
    stubReferrer("");
    window.history.replaceState({}, "", "/");
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  describe("configuração", () => {
    test("desabilitada retorna null", () => {
      vi.stubEnv("NEXT_PUBLIC_UMAMI_ENABLED", "false");
      vi.stubEnv("NEXT_PUBLIC_UMAMI_SCRIPT_URL", "https://umami.example.com/script.js");
      vi.stubEnv("NEXT_PUBLIC_UMAMI_WEBSITE_ID", "site");
      expect(getUmamiConfig()).toBeNull();
    });

    test("configuração ausente retorna null", () => {
      vi.stubEnv("NEXT_PUBLIC_UMAMI_ENABLED", "true");
      vi.stubEnv("NEXT_PUBLIC_UMAMI_SCRIPT_URL", "");
      vi.stubEnv("NEXT_PUBLIC_UMAMI_WEBSITE_ID", "");
      expect(getUmamiConfig()).toBeNull();
    });

    test("configuração válida retorna script e website id", () => {
      stubValidConfig();
      expect(getUmamiConfig()).toEqual({
        scriptUrl: "https://umami.example.com/script.js",
        websiteId: "11111111-1111-1111-1111-111111111111",
      });
    });

    test("host url opcional só aparece quando preenchida", () => {
      stubValidConfig();
      vi.stubEnv("NEXT_PUBLIC_UMAMI_HOST_URL", "https://collect.example.com");
      expect(getUmamiConfig()?.hostUrl).toBe("https://collect.example.com");
    });
  });

  describe("carregamento do script", () => {
    test("não injeta script quando desabilitado", () => {
      ensureUmamiScript();
      expect(document.querySelector(SCRIPT_SELECTOR)).toBeNull();
    });

    test("injeta o script uma única vez com os atributos corretos", () => {
      stubValidConfig();
      ensureUmamiScript();
      ensureUmamiScript();

      const scripts = document.querySelectorAll(SCRIPT_SELECTOR);
      expect(scripts).toHaveLength(1);
      const script = scripts[0] as HTMLScriptElement;
      expect(script.src).toBe("https://umami.example.com/script.js");
      expect(script.getAttribute("data-website-id")).toBe("11111111-1111-1111-1111-111111111111");
      expect(script.getAttribute("data-auto-track")).toBe("false");
    });
  });

  describe("page views", () => {
    test("registra page view com URL sanitizada explícita, sem título", () => {
      stubValidConfig();
      const track = installUmami();

      trackPageView("/dashboard");

      expect(track).toHaveBeenCalledTimes(1);
      expect(track).toHaveBeenCalledWith({ url: "/dashboard", referrer: "", title: "" });
    });

    test("deduplica page views consecutivas do mesmo caminho", () => {
      stubValidConfig();
      const track = installUmami();

      trackPageView("/dashboard");
      trackPageView("/dashboard");
      trackPageView(`/books/${UUID}`);

      expect(track).toHaveBeenCalledTimes(2);
    });

    test("não registra nada quando desabilitado", () => {
      const track = installUmami();
      trackPageView("/dashboard");
      expect(track).not.toHaveBeenCalled();
    });
  });

  describe("privacidade das URLs", () => {
    test("UUID, query string e hash nunca chegam ao tracker", () => {
      stubValidConfig();
      const track = installUmami();

      trackPageView(`/books/${UUID}?token=segredo#capitulo-1`);

      expect(track).toHaveBeenCalledWith({ url: "/books/{id}", referrer: "", title: "" });
      const serialized = JSON.stringify(track.mock.calls);
      expect(serialized).not.toContain(UUID);
      expect(serialized).not.toContain("token");
      expect(serialized).not.toContain("segredo");
      expect(serialized).not.toContain("#");
    });

    test("segmentos numéricos, hex e tokens opacos longos são normalizados", () => {
      expect(sanitizeTrackedPath("/books/42")).toBe("/books/{id}");
      expect(sanitizeTrackedPath("/books/9f8e7d6c5b4a39281706f5e4d3c2b1a0")).toBe("/books/{id}");
      expect(sanitizeTrackedPath("/share/V1StGXR8_Z5jdHi6B-myTasdfg")).toBe("/share/{id}");
      expect(sanitizeTrackedPath("/dashboard")).toBe("/dashboard");
      expect(sanitizeTrackedPath("")).toBe("/");
    });

    test("eventos usam a URL sanitizada da página atual, não a URL crua", () => {
      stubValidConfig();
      const track = installUmami();
      window.history.replaceState({}, "", `/books/${UUID}?aba=notas#cena`);

      trackEvent({ name: "book_created" });

      expect(track).toHaveBeenCalledWith({
        url: "/books/{id}",
        referrer: "",
        title: "",
        name: "book_created",
      });
      expect(JSON.stringify(track.mock.calls)).not.toContain(UUID);
    });

    test("referrer interno é sanitizado e externo vira somente a origem", () => {
      stubValidConfig();
      const track = installUmami();

      stubReferrer(`${window.location.origin}/books/${UUID}?q=1`);
      trackPageView("/dashboard");
      expect(track).toHaveBeenLastCalledWith({
        url: "/dashboard",
        referrer: `${window.location.origin}/books/{id}`,
        title: "",
      });

      stubReferrer("https://busca.example.com/resultados?q=meu-livro-secreto");
      trackPageView("/");
      expect(track).toHaveBeenLastCalledWith({
        url: "/",
        referrer: "https://busca.example.com",
        title: "",
      });
      expect(JSON.stringify(track.mock.calls)).not.toContain("meu-livro-secreto");
    });
  });

  describe("fila antes do carregamento do script", () => {
    function loadScript() {
      const script = document.querySelector(SCRIPT_SELECTOR) as HTMLScriptElement;
      script.dispatchEvent(new Event("load"));
    }

    test("navegações distintas antes do script carregar são preservadas em ordem", () => {
      stubValidConfig();
      ensureUmamiScript();

      trackPageView("/");
      trackPageView("/dashboard");
      trackPageView(`/books/${UUID}`);

      const track = installUmami();
      loadScript();

      expect(trackedUrls(track)).toEqual(["/", "/dashboard", "/books/{id}"]);
    });

    test("page view pendente é enviada uma única vez mesmo com load duplicado", () => {
      stubValidConfig();
      ensureUmamiScript();

      trackPageView("/");
      trackPageView("/");

      const track = installUmami();
      loadScript();
      loadScript();

      expect(track).toHaveBeenCalledTimes(1);
    });

    test("eventos personalizados antes do script carregar também são enfileirados", () => {
      stubValidConfig();
      ensureUmamiScript();

      trackEvent({ name: "scene_saved", data: { source: "AUTO_SAVE" } });

      const track = installUmami();
      loadScript();

      expect(track).toHaveBeenCalledWith({
        url: "/",
        referrer: "",
        title: "",
        name: "scene_saved",
        data: { source: "AUTO_SAVE" },
      });
    });

    test("eventos consecutivos idênticos na fila são deduplicados", () => {
      stubValidConfig();
      ensureUmamiScript();

      trackEvent({ name: "scene_saved", data: { source: "AUTO_SAVE" } });
      trackEvent({ name: "scene_saved", data: { source: "AUTO_SAVE" } });
      trackEvent({ name: "scene_saved", data: { source: "MANUAL_SAVE" } });

      const track = installUmami();
      loadScript();

      expect(track).toHaveBeenCalledTimes(2);
    });

    test("fila é limitada e descarta os itens mais antigos", () => {
      stubValidConfig();
      ensureUmamiScript();

      for (let page = 1; page <= 12; page++) {
        trackPageView(`/page-${page}`);
      }

      const track = installUmami();
      loadScript();

      expect(track).toHaveBeenCalledTimes(10);
      const urls = trackedUrls(track);
      expect(urls[0]).toBe("/page-3");
      expect(urls[urls.length - 1]).toBe("/page-12");
      expect(urls).not.toContain("/page-1");
      expect(urls).not.toContain("/page-2");
    });
  });

  describe("eventos", () => {
    test("envia evento permitido com propriedades enumeradas", () => {
      stubValidConfig();
      const track = installUmami();

      trackEvent({ name: "scene_saved", data: { source: "AUTO_SAVE" } });

      expect(track).toHaveBeenCalledWith({
        url: "/",
        referrer: "",
        title: "",
        name: "scene_saved",
        data: { source: "AUTO_SAVE" },
      });
    });

    test("envia evento de falha com categoria enumerada", () => {
      stubValidConfig();
      const track = installUmami();

      trackEvent({ name: "scene_analysis_failed", data: { category: "unavailable" } });

      expect(track).toHaveBeenCalledWith(
        expect.objectContaining({ name: "scene_analysis_failed", data: { category: "unavailable" } })
      );
    });

    test("remove propriedades fora da allowlist antes do envio", () => {
      stubValidConfig();
      const track = installUmami();

      trackEvent({
        name: "book_exported",
        data: {
          target: "manuscript",
          format: "md",
          bookId: "raw-id-must-not-leak",
          title: "Título privado",
        },
      } as unknown as AnalyticsEvent);

      expect(track).toHaveBeenCalledWith(
        expect.objectContaining({ name: "book_exported", data: { target: "manuscript", format: "md" } })
      );
      expect(JSON.stringify(track.mock.calls)).not.toContain("raw-id-must-not-leak");
    });

    test("descarta valores fora da enumeração", () => {
      stubValidConfig();
      const track = installUmami();

      trackEvent({
        name: "scene_analysis_failed",
        data: { category: "stack trace: NullPointerException" },
      } as unknown as AnalyticsEvent);

      expect(track).toHaveBeenCalledWith({
        url: "/",
        referrer: "",
        title: "",
        name: "scene_analysis_failed",
      });
    });

    test("descarta evento com nome fora da allowlist", () => {
      stubValidConfig();
      const track = installUmami();

      trackEvent({ name: "user_email_captured" } as unknown as AnalyticsEvent);

      expect(track).not.toHaveBeenCalled();
    });

    test("é no-op quando desabilitado ou sem tracker", () => {
      const track = installUmami();
      trackEvent({ name: "book_created" });
      expect(track).not.toHaveBeenCalled();

      stubValidConfig();
      delete window.umami;
      expect(() => trackEvent({ name: "book_created" })).not.toThrow();
    });

    test("falha do tracker não propaga para o produto", () => {
      stubValidConfig();
      window.umami = {
        track: () => {
          throw new Error("tracker offline");
        },
      };

      expect(() => trackEvent({ name: "book_created" })).not.toThrow();
      expect(() => trackPageView("/dashboard")).not.toThrow();
    });
  });
});
