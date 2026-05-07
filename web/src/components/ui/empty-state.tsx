import type { ReactNode } from "react";

type EmptyStateProps = {
  title: string;
  description?: ReactNode;
  className?: string;
};

export function EmptyState({ title, description, className = "" }: EmptyStateProps) {
  return (
    <div className={`rounded-lg border border-dashed border-zinc-300 bg-white/75 p-8 text-center shadow-sm ${className}`}>
      <h2 className="text-lg font-semibold text-zinc-950">{title}</h2>
      {description ? <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-zinc-600">{description}</p> : null}
    </div>
  );
}
