"use client";

import { FormEvent, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

type InlineCreateFormProps = {
  placeholder: string;
  buttonLabel: string;
  ariaLabel?: string;
  compact?: boolean;
  disabled?: boolean;
  onCreate: (title: string) => void;
};

export function InlineCreateForm({
  placeholder,
  buttonLabel,
  ariaLabel,
  compact = false,
  disabled,
  onCreate,
}: InlineCreateFormProps) {
  const [title, setTitle] = useState("");

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const nextTitle = title.trim();
    if (!nextTitle) {
      return;
    }

    onCreate(nextTitle);
    setTitle("");
  }

  return (
    <form onSubmit={handleSubmit} className="flex gap-2">
      <Input
        aria-label={ariaLabel ?? placeholder}
        value={title}
        onChange={(event) => setTitle(event.target.value)}
        placeholder={placeholder}
        className={`${compact ? "min-h-8 px-2 py-1 text-xs" : "min-h-9 px-3 py-2 text-sm"} min-w-0 flex-1`}
      />
      <Button type="submit" variant="secondary" size="sm" disabled={disabled || !title.trim()} className="shrink-0">
        {buttonLabel}
      </Button>
    </form>
  );
}
