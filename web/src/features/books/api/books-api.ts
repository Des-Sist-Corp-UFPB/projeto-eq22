import { apiRequest } from "@/lib/api/client";
import type { Book, CreateBookRequest, UpdateBookRequest } from "@/features/books/types";

export function listBooks() {
  return apiRequest<Book[]>("/api/books");
}

export function createBook(request: CreateBookRequest) {
  return apiRequest<Book>("/api/books", {
    method: "POST",
    body: request,
  });
}

export function updateBook(bookId: string, request: UpdateBookRequest) {
  return apiRequest<Book>(`/api/books/${bookId}`, {
    method: "PATCH",
    body: request,
  });
}

export function deleteBook(bookId: string) {
  return apiRequest<void>(`/api/books/${bookId}`, {
    method: "DELETE",
  });
}
