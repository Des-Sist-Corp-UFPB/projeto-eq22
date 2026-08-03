import { expect, test } from "@playwright/test";

/**
 * Covers thread #139-review-2's first finding: a 401 that ends the session — from a query or a
 * mutation, anywhere in the app — must clear every authenticated cache entry, not just the session
 * key, so a second login in the same tab can never render the previous tenant's data even
 * transiently. No page reload here on purpose: that would hand the SPA a fresh QueryClient and
 * prove nothing about the cache-clearing bug this covers.
 *
 * Runs with no storage state and logs in for itself, on purpose: every other spec shares one
 * JSESSIONID minted once by the setup project, and this test's whole point is to invalidate that
 * session — doing that to the shared one would 401 every other spec running at the same time.
 */
test.use({ storageState: { cookies: [], origins: [] } });

test("a 401 mid-session clears Autor A's data so logging in as Autor B in the same tab shows only B", async ({
  page,
  context,
}) => {
  test.setTimeout(60_000);

  const passwordA = process.env.IWRITE_DEMO_AUTOR_A_PASSWORD;
  const passwordB = process.env.IWRITE_DEMO_AUTOR_B_PASSWORD;
  expect(passwordA, "IWRITE_DEMO_AUTOR_A_PASSWORD must be set for the e2e run").toBeTruthy();
  expect(passwordB, "IWRITE_DEMO_AUTOR_B_PASSWORD must be set for the e2e run").toBeTruthy();

  async function csrfToken() {
    await page.request.get("/api/auth/csrf");
    const cookie = (await context.cookies()).find((c) => c.name === "XSRF-TOKEN");
    expect(cookie, "an XSRF-TOKEN cookie should be set").toBeTruthy();
    return cookie!.value;
  }

  const login = await page.request.post("/api/auth/login", {
    headers: { "X-XSRF-TOKEN": await csrfToken() },
    data: { email: "autor-a@iwrite.local", password: passwordA },
  });
  expect(login.status()).toBe(200);

  await page.goto("/library");
  await expect(page.getByText("A Cidade de Vidro")).toBeVisible();

  // Kills the session server-side without going through the UI's own logout button — the same
  // externally observable effect as an expired session or a revoked membership: the next
  // protected request gets 401. The tab is never reloaded, matching a session dying while the
  // app stays open. Logout's response clears the XSRF-TOKEN cookie along with the session, so the
  // next request that needs one (Autor B's login below) has to read it again afterwards.
  const logout = await page.request.post("/api/auth/logout", {
    headers: { "X-XSRF-TOKEN": await csrfToken() },
  });
  expect(logout.status()).toBe(204);

  // A real click through the UI, not a synthetic request: opening the seeded book issues a query
  // against the now-dead session and finds the 401 that ends it client-side.
  await page
    .getByRole("article")
    .filter({ hasText: "A Cidade de Vidro" })
    .getByRole("link", { name: "Abrir workspace" })
    .click();

  await expect(page).toHaveURL(/\/login/, { timeout: 20_000 });
  await expect(page.getByText("A Cidade de Vidro")).not.toBeVisible();

  await page.getByLabel("Email").fill("autor-b@iwrite.local");
  await page.getByLabel("Senha").fill(passwordB!);
  await page.getByRole("button", { name: "Entrar" }).click();

  await expect(page).toHaveURL(/\/library/);
  await expect(page.getByText("O Jardim Submerso")).toBeVisible();
  // Not even transiently: by the time the library screen can render at all, the swap already
  // happened server-side and the cache was cleared before this login's data arrived.
  await expect(page.getByText("A Cidade de Vidro")).not.toBeVisible();
});
