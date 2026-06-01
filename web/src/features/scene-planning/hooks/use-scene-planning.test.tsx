import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import React, { type ReactNode } from "react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { updateScenePlanning } from "@/features/scene-planning/api/scene-planning-api";
import { useUpdateScenePlanning } from "@/features/scene-planning/hooks/use-scene-planning";
import { queryKeys } from "@/lib/query/keys";
import { sceneForPlanning } from "@/test/fixtures";

vi.mock("@/features/scene-planning/api/scene-planning-api", () => ({
  updateScenePlanning: vi.fn(),
}));

const updateScenePlanningMock = vi.mocked(updateScenePlanning);

describe("useUpdateScenePlanning", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    updateScenePlanningMock.mockResolvedValue(sceneForPlanning);
  });

  test("invalidates scene, outline, and dashboard queries after a successful planning save", async () => {
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });
    const invalidateQueriesSpy = vi.spyOn(queryClient, "invalidateQueries");

    const { result } = renderHook(() => useUpdateScenePlanning("book-1", sceneForPlanning.id), {
      wrapper: ({ children }: { children: ReactNode }) => (
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      ),
    });

    await act(async () => {
      await result.current.mutateAsync({
        goal: "Goal",
        conflict: "Conflict",
        outcome: "Outcome",
        planningNotes: null,
        povCharacterId: null,
        participantCharacterIds: [],
        mainLocationId: null,
        itemIds: [],
      });
    });

    await waitFor(() => {
      expect(invalidateQueriesSpy).toHaveBeenCalledWith({ queryKey: queryKeys.scene(sceneForPlanning.id) });
      expect(invalidateQueriesSpy).toHaveBeenCalledWith({ queryKey: queryKeys.outline("book-1") });
      expect(invalidateQueriesSpy).toHaveBeenCalledWith({ queryKey: queryKeys.bookDashboard("book-1") });
    });
  });
});
