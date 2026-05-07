"use client";

import { useEffect, useMemo } from "react";
import type { Editor } from "@tiptap/react";
import { EditorContent, useEditor, type JSONContent } from "@tiptap/react";
import StarterKit from "@tiptap/starter-kit";

type TiptapEditorProps = {
  contentKey: string;
  initialContentJson?: JSONContent | string | null;
  initialContentText?: string | null;
  onChange: (contentJson: JSONContent, contentText: string) => void;
  className?: string;
};

function plainTextToDocument(text: string): JSONContent {
  const paragraphs = text.split(/\r?\n/);

  return {
    type: "doc",
    content: paragraphs.map((paragraph) => ({
      type: "paragraph",
      content: paragraph ? [{ type: "text", text: paragraph }] : undefined,
    })),
  };
}

function parseContentJson(contentJson: JSONContent | string | null | undefined): JSONContent | null {
  if (!contentJson) {
    return null;
  }

  if (typeof contentJson === "string") {
    try {
      const parsed = JSON.parse(contentJson) as JSONContent;
      return parsed?.type === "doc" ? parsed : null;
    } catch {
      return null;
    }
  }

  return contentJson.type === "doc" ? contentJson : null;
}

type ToolbarButtonProps = {
  label: string;
  active?: boolean;
  disabled?: boolean;
  onClick: () => void;
};

function ToolbarButton({ label, active, disabled, onClick }: ToolbarButtonProps) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className={`inline-flex min-h-8 items-center justify-center rounded-md border px-2.5 text-sm font-medium transition disabled:cursor-not-allowed disabled:opacity-50 ${
        active
          ? "border-zinc-900 bg-zinc-900 text-white"
          : "border-zinc-200 bg-white text-zinc-700 hover:border-zinc-300 hover:bg-zinc-50"
      }`}
    >
      {label}
    </button>
  );
}

function TiptapToolbar({ editor }: { editor: Editor | null }) {
  return (
    <div className="flex flex-wrap gap-2 rounded-t-lg border border-b-0 border-zinc-200 bg-zinc-50 px-3 py-2">
      <ToolbarButton label="B" active={editor?.isActive("bold")} disabled={!editor} onClick={() => editor?.chain().focus().toggleBold().run()} />
      <ToolbarButton label="I" active={editor?.isActive("italic")} disabled={!editor} onClick={() => editor?.chain().focus().toggleItalic().run()} />
      <ToolbarButton
        label="Parágrafo"
        active={editor?.isActive("paragraph")}
        disabled={!editor}
        onClick={() => editor?.chain().focus().setParagraph().run()}
      />
      <ToolbarButton
        label="Título"
        active={editor?.isActive("heading", { level: 2 })}
        disabled={!editor}
        onClick={() => editor?.chain().focus().toggleHeading({ level: 2 }).run()}
      />
      <ToolbarButton label="Desfazer" disabled={!editor || !editor.can().undo()} onClick={() => editor?.chain().focus().undo().run()} />
      <ToolbarButton label="Refazer" disabled={!editor || !editor.can().redo()} onClick={() => editor?.chain().focus().redo().run()} />
    </div>
  );
}

export function TiptapEditor({
  contentKey,
  initialContentJson,
  initialContentText,
  onChange,
  className = "",
}: TiptapEditorProps) {
  const initialContent = useMemo(
    () => parseContentJson(initialContentJson) ?? plainTextToDocument(initialContentText ?? ""),
    [contentKey, initialContentJson, initialContentText]
  );

  const editor = useEditor({
    extensions: [StarterKit],
    content: initialContent,
    immediatelyRender: false,
    editorProps: {
      attributes: {
        class: `min-h-[320px] rounded-lg border border-zinc-200 bg-white px-4 py-4 text-base leading-7 text-zinc-900 outline-none focus:border-zinc-800 ${className}`,
      },
    },
    onUpdate: ({ editor: updatedEditor }) => {
      onChange(updatedEditor.getJSON(), updatedEditor.getText());
    },
  });

  useEffect(() => {
    if (!editor) {
      return;
    }

    editor.commands.setContent(initialContent, { emitUpdate: false });
  }, [contentKey, editor]);

  return (
    <div>
      <TiptapToolbar editor={editor} />
      <EditorContent editor={editor} />
    </div>
  );
}
