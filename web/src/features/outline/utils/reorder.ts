export type ReorderableItem = {
  id: string;
};

export function getReorderedIds(items: ReorderableItem[], activeId: string, overId: string | null | undefined) {
  if (!overId || activeId === overId) {
    return null;
  }

  const activeIndex = items.findIndex((item) => item.id === activeId);
  const overIndex = items.findIndex((item) => item.id === overId);

  if (activeIndex < 0 || overIndex < 0) {
    return null;
  }

  const orderedIds = items.map((item) => item.id);
  const [movedId] = orderedIds.splice(activeIndex, 1);
  orderedIds.splice(overIndex, 0, movedId);

  return orderedIds;
}
