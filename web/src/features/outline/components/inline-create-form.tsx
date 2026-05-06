"use client";

import { FormEvent, useState } from "react";
import { Button } from "@/components/ui/button";

type InlineCreateFormProps = {
  placeholder: string;
  buttonLabel: string;
  disabled?: boolean;
  onCreate: (title: string) => void;
};

export function InlineCreateForm({ placeholder, buttonLabel, disabled, onCreate }: InlineCreateFormProps) {
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
      <input
        value={title}
        onChange={(event) => setTitle(event.target.value)}
        placeholder={placeholder}
        className="min-w-0 flex-1 rounded-md border border-zinc-300 bg-white px-2 py-1 text-sm outline-none focus:border-zinc-700"
      />
      <Button type="submit" variant="secondary" disabled={disabled || !title.trim()} className="min-h-8 px-2 py-1">
        {buttonLabel}
      </Button>
    </form>
  );
}
