import { describe, expect, test, vi } from "vitest";
import type { EditorView } from "@tiptap/pm/view";
import { handleTiptapTabKey } from "@/features/scenes/editor/tab-keymap";

describe("handleTiptapTabKey", () => {
  test("insere tabulacao no ponto atual do cursor", () => {
    const insertText = vi.fn().mockReturnValue("transaction");
    const preventDefault = vi.fn();
    const view = createView({ from: 4, to: 4, insertText });

    const handled = handleTiptapTabKey(view as unknown as EditorView, {
      key: "Tab",
      shiftKey: false,
      preventDefault,
    } as unknown as KeyboardEvent);

    expect(handled).toBe(true);
    expect(preventDefault).toHaveBeenCalled();
    expect(insertText).toHaveBeenCalledWith("\t", 4, 4);
    expect(view.dispatch).toHaveBeenCalledWith("transaction");
  });

  test("remove tabulacao anterior com Shift+Tab quando ela existe", () => {
    const deleteRange = vi.fn().mockReturnValue("transaction");
    const preventDefault = vi.fn();
    const view = createView({ from: 4, to: 4, previousCharacter: "\t", deleteRange });

    const handled = handleTiptapTabKey(view as unknown as EditorView, {
      key: "Tab",
      shiftKey: true,
      preventDefault,
    } as unknown as KeyboardEvent);

    expect(handled).toBe(true);
    expect(preventDefault).toHaveBeenCalled();
    expect(deleteRange).toHaveBeenCalledWith(3, 4);
    expect(view.dispatch).toHaveBeenCalledWith("transaction");
  });

  test("nao altera o texto quando Shift+Tab nao encontra tabulacao anterior", () => {
    const deleteRange = vi.fn().mockReturnValue("transaction");
    const preventDefault = vi.fn();
    const view = createView({ from: 4, to: 4, previousCharacter: "a", deleteRange });

    const handled = handleTiptapTabKey(view as unknown as EditorView, {
      key: "Tab",
      shiftKey: true,
      preventDefault,
    } as unknown as KeyboardEvent);

    expect(handled).toBe(true);
    expect(preventDefault).toHaveBeenCalled();
    expect(deleteRange).not.toHaveBeenCalled();
    expect(view.dispatch).not.toHaveBeenCalled();
  });

  test("ignora outras teclas", () => {
    const preventDefault = vi.fn();
    const view = createView({ from: 4, to: 4 });

    const handled = handleTiptapTabKey(view as unknown as EditorView, {
      key: "Enter",
      shiftKey: false,
      preventDefault,
    } as unknown as KeyboardEvent);

    expect(handled).toBe(false);
    expect(preventDefault).not.toHaveBeenCalled();
    expect(view.dispatch).not.toHaveBeenCalled();
  });
});

function createView({
  from,
  to,
  previousCharacter = "",
  insertText = vi.fn().mockReturnValue("transaction"),
  deleteRange = vi.fn().mockReturnValue("transaction"),
}: {
  from: number;
  to: number;
  previousCharacter?: string;
  insertText?: ReturnType<typeof vi.fn>;
  deleteRange?: ReturnType<typeof vi.fn>;
}) {
  return {
    state: {
      doc: {
        textBetween: vi.fn(() => previousCharacter),
      },
      selection: { from, to },
      tr: {
        delete: deleteRange,
        insertText,
      },
    },
    dispatch: vi.fn(),
  };
}
