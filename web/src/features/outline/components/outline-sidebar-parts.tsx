import type { ReactNode } from "react";

export function WordCount({ count }: { count: number }) {
  return <span className="shrink-0 text-xs tabular-nums text-zinc-500">{count} palavras</span>;
}

export function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="grid gap-1 text-xs">
      <span className="font-medium text-zinc-600">{label}</span>
      {children}
    </label>
  );
}
