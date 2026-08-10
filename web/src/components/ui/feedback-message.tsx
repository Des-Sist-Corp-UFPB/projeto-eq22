import type { HTMLAttributes } from "react";

type FeedbackMessageProps = HTMLAttributes<HTMLParagraphElement> & {
  variant?: "info" | "success" | "error";
};

export function FeedbackMessage({ className = "", variant = "info", ...props }: FeedbackMessageProps) {
  const variants = {
    info: "border-zinc-200 bg-white text-zinc-600",
    success: "border-emerald-200 bg-emerald-50 text-emerald-800",
    error: "border-red-200 bg-red-50 text-red-700",
  };

  return (
    <p
      role={variant === "error" ? "alert" : "status"}
      className={`rounded-md border px-3 py-2 text-sm ${variants[variant]} ${className}`}
      {...props}
    />
  );
}
