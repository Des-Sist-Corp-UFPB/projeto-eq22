"use client";

import { FormEvent, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

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
      <Input
        value={title}
        onChange={(event) => setTitle(event.target.value)}
        placeholder={placeholder}
        className="min-h-8 min-w-0 flex-1 px-2 py-1 text-sm"
      />
      <Button type="submit" variant="secondary" size="sm" disabled={disabled || !title.trim()}>
        {buttonLabel}
      </Button>
    </form>
  );
}
