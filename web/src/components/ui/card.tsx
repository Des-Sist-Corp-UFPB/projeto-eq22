import type { HTMLAttributes } from "react";

type CardProps = HTMLAttributes<HTMLDivElement>;

export function Card({ className = "", ...props }: CardProps) {
  return (
    <div
      className={`rounded-lg border border-zinc-200 bg-white shadow-sm shadow-zinc-200/60 ${className}`}
      {...props}
    />
  );
}
