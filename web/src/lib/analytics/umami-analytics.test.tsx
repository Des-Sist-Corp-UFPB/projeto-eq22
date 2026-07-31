import { render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { __resetAnalyticsForTest } from "@/lib/analytics/analytics";
import { UmamiAnalytics } from "@/lib/analytics/umami-analytics";

const mocks = vi.hoisted(() => ({
  usePathname: vi.fn<() => string>(),
}));

vi.mock("next/navigation", () => ({
  usePathname: mocks.usePathname,
}));

const SCRIPT_SELECTOR = "script[data-iwrite-umami]";

describe("UmamiAnalytics", () => {
  beforeEach(() => {
    __resetAnalyticsForTest();
    document.querySelectorAll(SCRIPT_SELECTOR).forEach((script) => script.remove());
    delete window.umami;
    mocks.usePathname.mockReturnValue("/");
    vi.stubEnv("NEXT_PUBLIC_UMAMI_ENABLED", "true");
    vi.stubEnv("NEXT_PUBLIC_UMAMI_SCRIPT_URL", "https://umami.example.com/script.js");
    vi.stubEnv("NEXT_PUBLIC_UMAMI_WEBSITE_ID", "11111111-1111-1111-1111-111111111111");
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  test("injeta o script uma única vez mesmo com re-render", () => {
    const { rerender } = render(<UmamiAnalytics />);
    rerender(<UmamiAnalytics />);

    expect(document.querySelectorAll(SCRIPT_SELECTOR)).toHaveLength(1);
  });

  test("registra a page view inicial e navegações client-side sem duplicar", () => {
    const track = vi.fn();
    window.umami = { track };

    const { rerender } = render(<UmamiAnalytics />);
    expect(track).toHaveBeenCalledTimes(1);

    rerender(<UmamiAnalytics />);
    expect(track).toHaveBeenCalledTimes(1);

    mocks.usePathname.mockReturnValue("/books/abc");
    rerender(<UmamiAnalytics />);
    expect(track).toHaveBeenCalledTimes(2);
  });

  test("sem configuração não injeta script nem quebra a renderização", () => {
    vi.stubEnv("NEXT_PUBLIC_UMAMI_ENABLED", "false");

    expect(() => render(<UmamiAnalytics />)).not.toThrow();
    expect(document.querySelector(SCRIPT_SELECTOR)).toBeNull();
  });
});
