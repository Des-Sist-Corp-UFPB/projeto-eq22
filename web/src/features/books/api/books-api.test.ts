import { beforeEach, describe, expect, test, vi } from "vitest";
import { createBook, deleteBook, listBooks, updateBook } from "@/features/books/api/books-api";

const mocks = vi.hoisted(() => ({ apiRequest: vi.fn() }));

vi.mock("@/lib/api/client", () => ({ apiRequest: mocks.apiRequest }));

describe("books API", () => {
  beforeEach(() => {
    mocks.apiRequest.mockReset().mockResolvedValue(undefined);
  });

  test("lists books", async () => {
    await listBooks();
    expect(mocks.apiRequest).toHaveBeenCalledWith("/api/books");
  });

  test("creates books", async () => {
    const request = { title: "Livro" };
    await createBook(request);
    expect(mocks.apiRequest).toHaveBeenCalledWith("/api/books", { method: "POST", body: request });
  });

  test("updates books", async () => {
    const request = { status: "WRITING" as const };
    await updateBook("book-1", request);
    expect(mocks.apiRequest).toHaveBeenCalledWith("/api/books/book-1", { method: "PATCH", body: request });
  });

  test("deletes books", async () => {
    await deleteBook("book-1");
    expect(mocks.apiRequest).toHaveBeenCalledWith("/api/books/book-1", { method: "DELETE" });
  });
});
