"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  createLocation,
  deleteLocation,
  getLocation,
  listLocations,
  updateLocation,
} from "@/features/locations/api/locations-api";
import type { LocationRequest, LocationUpdateRequest } from "@/features/locations/types";
import { queryKeys } from "@/lib/query/keys";

type UpdateLocationVariables = {
  locationId: string;
  payload: LocationUpdateRequest;
};

export function useLocations(bookId: string) {
  return useQuery({
    queryKey: queryKeys.locations(bookId),
    queryFn: () => listLocations(bookId),
    enabled: Boolean(bookId),
  });
}

export function useLocation(locationId: string | null) {
  return useQuery({
    queryKey: locationId ? queryKeys.location(locationId) : ["locations", "empty"],
    queryFn: () => getLocation(locationId as string),
    enabled: Boolean(locationId),
  });
}

export function useCreateLocation(bookId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (payload: LocationRequest) => createLocation(bookId, payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.locations(bookId) });
    },
  });
}

export function useUpdateLocation(bookId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ locationId, payload }: UpdateLocationVariables) => updateLocation(locationId, payload),
    onSuccess: (location) => {
      void queryClient.setQueryData(queryKeys.location(location.id), location);
      void queryClient.invalidateQueries({ queryKey: queryKeys.locations(bookId) });
    },
  });
}

export function useDeleteLocation(bookId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (locationId: string) => deleteLocation(locationId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.locations(bookId) });
    },
  });
}
