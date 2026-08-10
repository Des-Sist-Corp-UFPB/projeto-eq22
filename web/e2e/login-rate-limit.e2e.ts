import { expect, test } from "@playwright/test";

/**
 * Covers thread #139-review-2's fourth finding: a 429 from the login endpoint must show an
 * actionable "wait" message, not the generic "service unavailable" text every other non-401
 * failure gets.
 *
 * Runs with no storage state on purpose: every other spec shares one JSESSIONID minted once by
 * the setup project, and logging that session out here (to reach an unauthenticated /login) would
 * kill it for every spec that still expects to be Autor A. A fresh, anonymous context needs
 * nothing from that session — only a CSRF cookie, which the (permitAll) csrf endpoint hands out
 * regardless of auth state — so it can spend one throwaway account's failure budget (default 8
 * per iwrite.auth.login-rate-limit) via direct API calls against an email nothing else in this
 * suite ever touches, without spending the shared origin budget or colliding with a real account.
 */
test.use({ storageState: { cookies: [], origins: [] } });

test("shows the rate-limit message on screen once an account's attempt budget is spent", async ({
  page,
  context,
}) => {
  test.setTimeout(60_000);

  const targetEmail = `rate-limit-e2e-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@iwrite.local`;

  await page.request.get("/api/auth/csrf");
  const csrfCookie = (await context.cookies()).find((cookie) => cookie.name === "XSRF-TOKEN");
  expect(csrfCookie, "an XSRF-TOKEN cookie should be set").toBeTruthy();

  for (let attempt = 0; attempt < 8; attempt++) {
    await page.request.post("/api/auth/login", {
      headers: { "X-XSRF-TOKEN": csrfCookie!.value },
      data: { email: targetEmail, password: "senha-errada" },
    });
  }

  await page.goto("/login");
  await page.getByLabel("Email").fill(targetEmail);
  await page.getByLabel("Senha").fill("senha-qualquer");
  await page.getByRole("button", { name: "Entrar" }).click();

  // Next.js's own route announcer also carries role="alert" (empty text, for screen readers on
  // navigation); scope to the form's own feedback message to avoid matching both.
  const alert = page.locator("form").getByRole("alert");
  await expect(alert).toHaveText("Muitas tentativas de login. Aguarde um momento e tente novamente.");
  // Never the account's own email or any hint of which dimension tripped.
  await expect(alert).not.toContainText(targetEmail);
});
