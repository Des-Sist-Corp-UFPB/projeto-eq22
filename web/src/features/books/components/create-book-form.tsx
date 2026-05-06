"use client";

import { FormEvent, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { createBook } from "@/features/books/api/books-api";
import { queryKeys } from "@/lib/query/keys";
import { Button } from "@/components/ui/button";
import { TextAreaField, TextField } from "@/components/ui/text-field";

export function CreateBookForm() {
  const queryClient = useQueryClient();
  const [title, setTitle] = useState("");
  const [subtitle, setSubtitle] = useState("");
  const [description, setDescription] = useState("");

  const mutation = useMutation({
    mutationFn: createBook,
    onSuccess: () => {
      setTitle("");
      setSubtitle("");
      setDescription("");
      void queryClient.invalidateQueries({ queryKey: queryKeys.books });
    },
  });

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!title.trim()) {
      return;
    }

    mutation.mutate({
      title: title.trim(),
      subtitle: subtitle.trim() || undefined,
      description: description.trim() || undefined,
    });
  }

  return (
    <form onSubmit={handleSubmit} className="grid gap-3 rounded-md border border-zinc-200 bg-white p-4">
      <TextField label="Titulo" value={title} onChange={(event) => setTitle(event.target.value)} />
      <TextField label="Subtitulo" value={subtitle} onChange={(event) => setSubtitle(event.target.value)} />
      <TextAreaField
        label="Descricao"
        value={description}
        rows={3}
        onChange={(event) => setDescription(event.target.value)}
      />
      <Button type="submit" disabled={mutation.isPending || !title.trim()}>
        {mutation.isPending ? "Criando..." : "Criar livro"}
      </Button>
      {mutation.isError ? <p className="text-sm text-red-700">{mutation.error.message}</p> : null}
    </form>
  );
}
