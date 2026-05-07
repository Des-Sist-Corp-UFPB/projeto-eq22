"use client";

import { EditorContent, useEditor, type JSONContent } from "@tiptap/react";
import StarterKit from "@tiptap/starter-kit";

type TiptapEditorProps = {
  initialContentJson?: JSONContent | string | null;
  initialContentText?: string | null;
  onChange: (contentJson: JSONContent, contentText: string) => void;
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

export function TiptapEditor({ initialContentJson, initialContentText, onChange }: TiptapEditorProps) {
  const initialContent = parseContentJson(initialContentJson) ?? plainTextToDocument(initialContentText ?? "");

  const editor = useEditor({
    extensions: [StarterKit],
    content: initialContent,
    immediatelyRender: false,
    editorProps: {
      attributes: {
        class:
          "min-h-[320px] rounded-lg border border-zinc-200 bg-white px-4 py-4 text-base leading-7 text-zinc-900 outline-none focus:border-zinc-800",
      },
    },
    onUpdate: ({ editor: updatedEditor }) => {
      onChange(updatedEditor.getJSON(), updatedEditor.getText());
    },
  });

  return <EditorContent editor={editor} />;
}
