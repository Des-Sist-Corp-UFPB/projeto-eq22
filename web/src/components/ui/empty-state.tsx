import type { ReactNode } from "react";

type EmptyStateProps = {
  title: string;
  description?: ReactNode;
  size?: "sm" | "md";
  className?: string;
};

export function EmptyState({ title, description, size = "md", className = "" }: EmptyStateProps) {
  const sizes = {
    sm: "p-3 text-left",
    md: "p-8 text-center",
  };
  const titleSizes = {
    sm: "text-sm",
    md: "text-lg",
  };

  return (
    <div className={`rounded-lg border border-dashed border-zinc-300 bg-white/75 shadow-sm ${sizes[size]} ${className}`}>
      <h2 className={`font-semibold text-zinc-950 ${titleSizes[size]}`}>{title}</h2>
      {description ? (
        <p className={`${size === "md" ? "mx-auto max-w-md" : ""} mt-2 text-sm leading-6 text-zinc-600`}>
          {description}
        </p>
      ) : null}
    </div>
  );
}
