"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createItem, deleteItem, getItem, listItems, updateItem } from "@/features/items/api/items-api";
import type { ItemRequest, ItemUpdateRequest } from "@/features/items/types";
import { queryKeys } from "@/lib/query/keys";

type UpdateItemVariables = {
  itemId: string;
  payload: ItemUpdateRequest;
};

export function useItems(bookId: string) {
  return useQuery({
    queryKey: queryKeys.items(bookId),
    queryFn: () => listItems(bookId),
    enabled: Boolean(bookId),
  });
}

export function useItem(itemId: string | null) {
  return useQuery({
    queryKey: itemId ? queryKeys.item(itemId) : ["items", "empty"],
    queryFn: () => getItem(itemId as string),
    enabled: Boolean(itemId),
  });
}

export function useCreateItem(bookId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (payload: ItemRequest) => createItem(bookId, payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.items(bookId) });
    },
  });
}

export function useUpdateItem(bookId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ itemId, payload }: UpdateItemVariables) => updateItem(itemId, payload),
    onSuccess: (item) => {
      void queryClient.setQueryData(queryKeys.item(item.id), item);
      void queryClient.invalidateQueries({ queryKey: queryKeys.items(bookId) });
    },
  });
}

export function useDeleteItem(bookId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (itemId: string) => deleteItem(itemId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.items(bookId) });
    },
  });
}
