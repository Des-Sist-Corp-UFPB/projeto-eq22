import type { InputHTMLAttributes, TextareaHTMLAttributes } from "react";

type TextFieldProps = InputHTMLAttributes<HTMLInputElement> & {
  label: string;
};

export function TextField({ label, className = "", ...props }: TextFieldProps) {
  return (
    <label className="grid gap-1 text-sm">
      <span className="font-medium text-zinc-700">{label}</span>
      <input
        className={`min-h-10 rounded-md border border-zinc-300 bg-white px-3 py-2 outline-none focus:border-zinc-700 ${className}`}
        {...props}
      />
    </label>
  );
}

type TextAreaFieldProps = TextareaHTMLAttributes<HTMLTextAreaElement> & {
  label: string;
};

export function TextAreaField({ label, className = "", ...props }: TextAreaFieldProps) {
  return (
    <label className="grid gap-1 text-sm">
      <span className="font-medium text-zinc-700">{label}</span>
      <textarea
        className={`rounded-md border border-zinc-300 bg-white px-3 py-2 outline-none focus:border-zinc-700 ${className}`}
        {...props}
      />
    </label>
  );
}
