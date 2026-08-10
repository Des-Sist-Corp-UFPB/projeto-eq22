import type { TextareaHTMLAttributes } from "react";

export type TextareaProps = TextareaHTMLAttributes<HTMLTextAreaElement>;

export function Textarea({ className = "", ...props }: TextareaProps) {
  return (
    <textarea
      className={`rounded-md border border-zinc-300 bg-white px-3 py-2 outline-none transition focus:border-zinc-700 disabled:cursor-not-allowed disabled:bg-zinc-100 disabled:text-zinc-500 ${className}`}
      {...props}
    />
  );
}
