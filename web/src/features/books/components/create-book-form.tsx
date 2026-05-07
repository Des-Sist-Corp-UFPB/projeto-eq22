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
  const [successMessage, setSuccessMessage] = useState("");

  const mutation = useMutation({
    mutationFn: createBook,
    onSuccess: () => {
      setTitle("");
      setSubtitle("");
      setDescription("");
      setSuccessMessage("Livro criado com sucesso.");
      void queryClient.invalidateQueries({ queryKey: queryKeys.books });
    },
  });

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSuccessMessage("");

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
    <form
      onSubmit={handleSubmit}
      className="grid gap-4 rounded-lg border border-zinc-200 bg-white p-5 shadow-sm shadow-zinc-200/60"
    >
      <TextField
        label="Título do livro"
        value={title}
        placeholder="Ex.: A cidade de vidro"
        onChange={(event) => {
          setTitle(event.target.value);
          setSuccessMessage("");
        }}
      />
      <TextField
        label="Subtítulo"
        value={subtitle}
        placeholder="Opcional"
        onChange={(event) => setSubtitle(event.target.value)}
      />
      <TextAreaField
        label="Descrição"
        value={description}
        rows={4}
        placeholder="Uma frase sobre a ideia central, gênero ou promessa da história."
        onChange={(event) => setDescription(event.target.value)}
      />

      <Button
        type="submit"
        disabled={mutation.isPending || !title.trim()}
        className="mt-1 min-h-11 w-full disabled:cursor-not-allowed disabled:opacity-70"
      >
        {mutation.isPending ? "Criando..." : "Criar livro"}
      </Button>

      {successMessage ? (
        <p className="rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-800">
          {successMessage}
        </p>
      ) : null}

      {mutation.isError ? (
        <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          Não foi possível criar o livro agora. Verifique se o backend está rodando em localhost:8085.
        </p>
      ) : null}
    </form>
  );
}
