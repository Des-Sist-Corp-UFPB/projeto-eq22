import { expect, test } from "@playwright/test";

test("loads the Home page", async ({ page }) => {
  await page.goto("/");

  await expect(page.getByRole("heading", { name: "IWrite" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Criar livro" })).toBeVisible();
});
