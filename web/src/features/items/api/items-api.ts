import { apiRequest } from "@/lib/api/client";
import type { ItemRequest, ItemResponse, ItemUpdateRequest } from "@/features/items/types";

export function listItems(bookId: string) {
  return apiRequest<ItemResponse[]>(`/api/books/${bookId}/items`);
}

export function getItem(itemId: string) {
  return apiRequest<ItemResponse>(`/api/items/${itemId}`);
}

export function createItem(bookId: string, payload: ItemRequest) {
  return apiRequest<ItemResponse>(`/api/books/${bookId}/items`, {
    method: "POST",
    body: payload,
  });
}

export function updateItem(itemId: string, payload: ItemUpdateRequest) {
  return apiRequest<ItemResponse>(`/api/items/${itemId}`, {
    method: "PATCH",
    body: payload,
  });
}

export function deleteItem(itemId: string) {
  return apiRequest<void>(`/api/items/${itemId}`, {
    method: "DELETE",
  });
}
