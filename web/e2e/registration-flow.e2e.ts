import { expect, test } from "@playwright/test";

/**
 * Covers issue #143: public registration end to end. Runs with no shared storage state — every
 * other spec reuses one JSESSIONID minted once by the setup project (Autor A), and this spec
 * creates and authenticates as a brand-new account, so sharing that cookie jar would mix identities.
 * Uses a unique, generated email so the spec is safe to re-run and never collides with another run.
 */
test.use({ storageState: { cookies: [], origins: [] } });

test("registers, restores the session on reload, and only the new account's own book ever appears", async ({
  page,
}) => {
  test.setTimeout(90_000);

  const suffix = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const email = `e2e-registro-${suffix}@iwrite.local`;
  const displayName = `Autora E2E ${suffix}`;
  const password = "senha-e2e-valida-1";
  const bookTitle = `Primeiro livro ${suffix}`;

  await page.goto("/login");
  await page.getByRole("link", { name: "Criar conta" }).click();
  await expect(page).toHaveURL(/\/register/);

  await page.getByLabel("Nome de exibição").fill(displayName);
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Senha", { exact: true }).fill(password);
  await page.getByLabel("Confirme a senha").fill(password);
  await page.getByRole("button", { name: "Criar conta" }).click();

  await expect(page).toHaveURL(/\/library/);
  // The freshly created workspace starts with nothing in it.
  await expect(page.getByText("Sua biblioteca ainda está vazia")).toBeVisible();
  // Never a demo user's book, even transiently, right after the swap into the new session.
  await expect(page.getByText("A Cidade de Vidro")).not.toBeVisible();
  await expect(page.getByText("O Jardim Submerso")).not.toBeVisible();

  await page.reload();
  await expect(page).toHaveURL(/\/library/);
  await expect(page.getByText("Sua biblioteca ainda está vazia")).toBeVisible();

  await page.getByPlaceholder("Ex.: A cidade de vidro").fill(bookTitle);
  await page.getByRole("button", { name: "Criar livro" }).click();
  const bookCard = page.getByRole("article").filter({ hasText: bookTitle });
  await expect(bookCard).toBeVisible();

  await page.getByRole("button", { name: "Sair" }).click();
  await expect(page).toHaveURL(/\/login/);

  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Senha").fill(password);
  await page.getByRole("button", { name: "Entrar" }).click();

  await expect(page).toHaveURL(/\/library/);
  await expect(page.getByText(bookTitle)).toBeVisible();
  // Only this account's own book, still never a demo user's.
  await expect(page.getByText("A Cidade de Vidro")).not.toBeVisible();
  await expect(page.getByText("O Jardim Submerso")).not.toBeVisible();
});
