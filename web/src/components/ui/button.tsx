import type { ButtonHTMLAttributes } from "react";

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: "primary" | "secondary" | "ghost";
  size?: "sm" | "md";
};

export function Button({ className = "", variant = "primary", size = "md", ...props }: ButtonProps) {
  const variants = {
    primary: "bg-zinc-900 text-white hover:bg-zinc-700 disabled:bg-zinc-400",
    secondary: "border border-zinc-300 bg-white text-zinc-900 hover:bg-zinc-100",
    ghost: "text-zinc-700 hover:bg-zinc-100",
  };
  const sizes = {
    sm: "min-h-8 px-2 py-1",
    md: "min-h-9 px-3 py-2",
  };

  return (
    <button
      className={`inline-flex items-center justify-center rounded-md text-sm font-medium transition disabled:cursor-not-allowed disabled:opacity-70 ${sizes[size]} ${variants[variant]} ${className}`}
      {...props}
    />
  );
}
