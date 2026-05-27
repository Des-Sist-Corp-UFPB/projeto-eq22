import { apiRequest } from "@/lib/api/client";
import type { LocationRequest, LocationResponse, LocationUpdateRequest } from "@/features/locations/types";

export function listLocations(bookId: string) {
  return apiRequest<LocationResponse[]>(`/api/books/${bookId}/locations`);
}

export function getLocation(locationId: string) {
  return apiRequest<LocationResponse>(`/api/locations/${locationId}`);
}

export function createLocation(bookId: string, payload: LocationRequest) {
  return apiRequest<LocationResponse>(`/api/books/${bookId}/locations`, {
    method: "POST",
    body: payload,
  });
}

export function updateLocation(locationId: string, payload: LocationUpdateRequest) {
  return apiRequest<LocationResponse>(`/api/locations/${locationId}`, {
    method: "PATCH",
    body: payload,
  });
}

export function deleteLocation(locationId: string) {
  return apiRequest<void>(`/api/locations/${locationId}`, {
    method: "DELETE",
  });
}
