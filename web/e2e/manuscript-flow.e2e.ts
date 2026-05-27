import { expect, test } from "@playwright/test";
import { readFile } from "node:fs/promises";

test("creates, saves, reloads, and exports a Markdown manuscript", async ({ page }) => {
  test.setTimeout(90_000);

  const suffix = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const bookTitle = `E2E Manuscript ${suffix}`;
  const sectionTitle = `Part ${suffix}`;
  const chapterTitle = `Chapter ${suffix}`;
  const sceneTitle = `Scene ${suffix}`;
  const sceneContent = `Distinctive manuscript content ${suffix}`;

  await expect
    .poll(async () => {
      const response = await page.request.get("http://localhost:8086/api/books");
      return response.status();
    })
    .toBe(200);

  await page.goto("/");

  await expect(page.getByRole("heading", { name: "IWrite" })).toBeVisible();

  await page.getByPlaceholder("Ex.: A cidade de vidro").fill(bookTitle);
  await page.getByRole("button", { name: "Criar livro" }).click();

  const bookCard = page.getByRole("article").filter({ hasText: bookTitle });
  await expect(bookCard).toBeVisible();
  await bookCard.getByRole("link", { name: "Abrir workspace" }).click();

  await expect(page.getByRole("heading", { name: bookTitle }).first()).toBeVisible();

  await page.getByPlaceholder(/Nova se/).fill(sectionTitle);
  await page.keyboard.press("Enter");
  await expect(page.getByText(sectionTitle, { exact: true })).toBeVisible();

  await page.getByPlaceholder(/Novo cap/).fill(chapterTitle);
  await page.keyboard.press("Enter");
  await expect(page.getByText(chapterTitle, { exact: true })).toBeVisible();

  await page.getByPlaceholder("Nova cena").fill(sceneTitle);
  await page.keyboard.press("Enter");
  await expect(page.getByText(sceneTitle, { exact: true }).first()).toBeVisible();
  await page.getByRole("button").filter({ hasText: sceneTitle }).click();

  const editor = page.getByTestId("scene-content-editor");
  await expect(editor).toBeVisible();

  await editor.fill(sceneContent);
  await expect(page.getByText("Digitando...").first()).toBeVisible();
  await page.getByRole("button", { name: /Salvar conte/ }).click();
  await expect(page.getByText("Salvo").first()).toBeVisible();

  await page.reload();
  await expect(page.getByRole("heading", { name: bookTitle }).first()).toBeVisible();
  await page.getByRole("button").filter({ hasText: sceneTitle }).click();
  await expect(editor).toContainText(sceneContent);

  await page.getByRole("button", { name: "Exportar manuscrito" }).click();
  await expect(page.getByLabel("Markdown (.md)")).toBeChecked();

  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "Baixar manuscrito" }).click();
  const download = await downloadPromise;
  const downloadPath = await download.path();

  expect(downloadPath).not.toBeNull();

  const exportedContent = await readFile(downloadPath as string, "utf8");
  expect(exportedContent).toContain(bookTitle);
  expect(exportedContent).toContain(sceneContent);
});
