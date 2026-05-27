import type { EditorView } from "@tiptap/pm/view";

const TAB_CHARACTER = "\t";

export function handleTiptapTabKey(view: EditorView, event: KeyboardEvent) {
  if (event.key !== "Tab") {
    return false;
  }

  event.preventDefault();

  if (event.shiftKey) {
    removePreviousTab(view);
    return true;
  }

  insertTab(view);
  return true;
}

function insertTab(view: EditorView) {
  const { from, to } = view.state.selection;
  view.dispatch(view.state.tr.insertText(TAB_CHARACTER, from, to));
}

function removePreviousTab(view: EditorView) {
  const { from, to } = view.state.selection;
  if (from !== to || from <= 0) {
    return;
  }

  const previousCharacter = view.state.doc.textBetween(from - 1, from, "\n", "\n");
  if (previousCharacter === TAB_CHARACTER) {
    view.dispatch(view.state.tr.delete(from - 1, from));
  }
}
