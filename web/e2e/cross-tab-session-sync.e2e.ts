import { expect, test } from "@playwright/test";

/**
 * Covers thread #139-review-4's finding: cookies and the HttpSession are shared across same-origin
 * tabs, but each tab keeps its own QueryClient with staleTime: Infinity and no focus refetch — so a
 * logout and a different login in one tab left every other same-origin tab showing the first
 * identity's cache indefinitely, with every later request still using whichever cookie the second
 * tab left behind. session-sync.test.tsx covers the reconciliation mechanism's races directly
 * (stale queries, stale mutations, the storage-event fallback); this drives two real pages of the
 * same browser context so BroadcastChannel actually crosses a process boundary, not just a mock.
 *
 * Runs with no storage state and logs in for itself, on purpose: every other spec shares one
 * JSESSIONID minted once by the setup project, and this test's whole point is to end that session
 * from a second tab - doing that to the shared one would 401 every other spec running at the same
 * time.
 */
test.use({ storageState: { cookies: [], origins: [] } });

test("logging out and back in as a different author in tab 2 reconciles tab 1 without ever showing A again", async ({
  page,
  context,
}) => {
  test.setTimeout(60_000);

  const passwordA = process.env.IWRITE_DEMO_AUTOR_A_PASSWORD;
  const passwordB = process.env.IWRITE_DEMO_AUTOR_B_PASSWORD;
  expect(passwordA, "IWRITE_DEMO_AUTOR_A_PASSWORD must be set for the e2e run").toBeTruthy();
  expect(passwordB, "IWRITE_DEMO_AUTOR_B_PASSWORD must be set for the e2e run").toBeTruthy();

  // Tab 1: a real UI login as Autor A, the identity the rest of this test watches for a leak.
  await page.goto("/login");
  await page.getByLabel("Email").fill("autor-a@iwrite.local");
  await page.getByLabel("Senha").fill(passwordA!);
  await page.getByRole("button", { name: "Entrar" }).click();
  await expect(page).toHaveURL(/\/library/);
  await expect(page.getByText("A Cidade de Vidro")).toBeVisible();

  // Tab 2: same browser context, so the same cookie jar and the same origin - exactly what
  // BroadcastChannel and localStorage need to reach tab 1 for real.
  const page2 = await context.newPage();
  await page2.goto("/library");
  await expect(page2.getByText("A Cidade de Vidro")).toBeVisible();

  await page2.getByRole("button", { name: "Sair" }).click();
  await expect(page2).toHaveURL(/\/login/);

  await page2.getByLabel("Email").fill("autor-b@iwrite.local");
  await page2.getByLabel("Senha").fill(passwordB!);
  await page2.getByRole("button", { name: "Entrar" }).click();
  await expect(page2).toHaveURL(/\/library/);
  await expect(page2.getByText("O Jardim Submerso")).toBeVisible();

  // Tab 1 was never reloaded and never touched since Autor A's own login above - only the
  // BroadcastChannel events from tab 2's logout and tab 2's login should have reached it.
  await expect(page.getByText("Verificando sessão…").or(page.getByText("O Jardim Submerso"))).toBeVisible({
    timeout: 20_000,
  });
  await expect(page.getByText("O Jardim Submerso")).toBeVisible({ timeout: 20_000 });
  // Not even transiently: session-sync.ts purges every authenticated cache entry before asking
  // /api/auth/me again, so there is no window in which A's book could have painted.
  await expect(page.getByText("A Cidade de Vidro")).not.toBeVisible();
  await expect(page.getByText("Autor B", { exact: true })).toBeVisible();

  // A protected read issued from tab 1 after the swap must be authorized under B's tenant, not A's:
  // opening the book only B's library has succeeds at all only if tab 1 is really operating as B
  // server-side too, not just showing B's name in a stale corner of the UI. manuscript-flow.e2e.ts
  // already covers an actual write (saving a scene) end to end; repeating that here on top of a
  // fresh two-tab swap would only add flake without covering anything this read doesn't already.
  await page
    .getByRole("article")
    .filter({ hasText: "O Jardim Submerso" })
    .getByRole("link", { name: "Abrir workspace" })
    .click();
  await expect(page).toHaveURL(/\/books\/.+/);
  await expect(page.getByRole("heading", { name: "O Jardim Submerso" }).first()).toBeVisible();
});
