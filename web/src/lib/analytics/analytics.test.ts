import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import {
  __resetAnalyticsForTest,
  ensureUmamiScript,
  getUmamiConfig,
  trackEvent,
  trackPageView,
  type AnalyticsEvent,
} from "@/lib/analytics/analytics";

const SCRIPT_SELECTOR = "script[data-iwrite-umami]";

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

describe("analytics", () => {
  beforeEach(() => {
    __resetAnalyticsForTest();
    document.querySelectorAll(SCRIPT_SELECTOR).forEach((script) => script.remove());
    delete window.umami;
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
    test("registra page view quando o tracker está carregado", () => {
      stubValidConfig();
      const track = installUmami();

      trackPageView("/dashboard");

      expect(track).toHaveBeenCalledTimes(1);
      expect(track).toHaveBeenCalledWith();
    });

    test("deduplica page views consecutivas do mesmo caminho", () => {
      stubValidConfig();
      const track = installUmami();

      trackPageView("/dashboard");
      trackPageView("/dashboard");
      trackPageView("/books/abc");

      expect(track).toHaveBeenCalledTimes(2);
    });

    test("page view inicial pendente é enviada uma única vez após o script carregar", () => {
      stubValidConfig();
      ensureUmamiScript();

      trackPageView("/");
      trackPageView("/");

      const track = installUmami();
      const script = document.querySelector(SCRIPT_SELECTOR) as HTMLScriptElement;
      script.dispatchEvent(new Event("load"));
      script.dispatchEvent(new Event("load"));

      expect(track).toHaveBeenCalledTimes(1);
    });

    test("não registra nada quando desabilitado", () => {
      const track = installUmami();
      trackPageView("/dashboard");
      expect(track).not.toHaveBeenCalled();
    });
  });

  describe("eventos", () => {
    test("envia evento de sucesso permitido com propriedades enumeradas", () => {
      stubValidConfig();
      const track = installUmami();

      trackEvent({ name: "scene_saved", data: { source: "AUTO_SAVE" } });

      expect(track).toHaveBeenCalledWith("scene_saved", { source: "AUTO_SAVE" });
    });

    test("envia evento de falha com categoria enumerada", () => {
      stubValidConfig();
      const track = installUmami();

      trackEvent({ name: "scene_analysis_failed", data: { category: "unavailable" } });

      expect(track).toHaveBeenCalledWith("scene_analysis_failed", { category: "unavailable" });
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

      expect(track).toHaveBeenCalledWith("book_exported", { target: "manuscript", format: "md" });
    });

    test("descarta valores fora da enumeração", () => {
      stubValidConfig();
      const track = installUmami();

      trackEvent({
        name: "scene_analysis_failed",
        data: { category: "stack trace: NullPointerException" },
      } as unknown as AnalyticsEvent);

      expect(track).toHaveBeenCalledWith("scene_analysis_failed");
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
