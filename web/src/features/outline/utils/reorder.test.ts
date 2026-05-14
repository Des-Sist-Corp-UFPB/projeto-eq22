import { describe, expect, test } from "vitest";
import { getReorderedIds } from "@/features/outline/utils/reorder";

const scenes = [{ id: "scene-1" }, { id: "scene-2" }, { id: "scene-3" }];
const chapters = [{ id: "chapter-1" }, { id: "chapter-2" }, { id: "chapter-3" }];

describe("getReorderedIds", () => {
  test("move um item para depois de outro item", () => {
    expect(getReorderedIds(scenes, "scene-1", "scene-3")).toEqual(["scene-2", "scene-3", "scene-1"]);
  });

  test("move um item para antes de outro item", () => {
    expect(getReorderedIds(scenes, "scene-3", "scene-1")).toEqual(["scene-3", "scene-1", "scene-2"]);
  });

  test("retorna null quando active e over sao iguais", () => {
    expect(getReorderedIds(scenes, "scene-2", "scene-2")).toBeNull();
  });

  test("retorna null quando algum id nao existe", () => {
    expect(getReorderedIds(scenes, "missing", "scene-2")).toBeNull();
    expect(getReorderedIds(scenes, "scene-2", "missing")).toBeNull();
    expect(getReorderedIds(scenes, "scene-2", null)).toBeNull();
  });

  test("tambem calcula nova ordem para capitulos", () => {
    expect(getReorderedIds(chapters, "chapter-2", "chapter-1")).toEqual(["chapter-2", "chapter-1", "chapter-3"]);
  });
});
