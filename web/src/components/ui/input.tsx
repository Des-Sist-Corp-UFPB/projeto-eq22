import type { InputHTMLAttributes } from "react";

export type InputProps = InputHTMLAttributes<HTMLInputElement>;

export function Input({ className = "", ...props }: InputProps) {
  return (
    <input
      className={`min-h-10 rounded-md border border-zinc-300 bg-white px-3 py-2 outline-none transition focus:border-zinc-700 disabled:cursor-not-allowed disabled:bg-zinc-100 disabled:text-zinc-500 ${className}`}
      {...props}
    />
  );
}
