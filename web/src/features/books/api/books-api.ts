import { apiRequest } from "@/lib/api/client";
import type { Book, CreateBookRequest } from "@/features/books/types";

export function listBooks() {
  return apiRequest<Book[]>("/api/books");
}

export function createBook(request: CreateBookRequest) {
  return apiRequest<Book>("/api/books", {
    method: "POST",
    body: request,
  });
}
