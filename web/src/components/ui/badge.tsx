import type { HTMLAttributes } from "react";

type BadgeProps = HTMLAttributes<HTMLSpanElement> & {
  variant?: "neutral" | "outline";
};

export function Badge({ className = "", variant = "neutral", ...props }: BadgeProps) {
  const variants = {
    neutral: "bg-zinc-100 text-zinc-700",
    outline: "border border-zinc-200 bg-white text-zinc-600 shadow-sm",
  };

  return (
    <span
      className={`inline-flex w-fit items-center rounded-full px-2.5 py-1 text-xs font-medium ${variants[variant]} ${className}`}
      {...props}
    />
  );
}
