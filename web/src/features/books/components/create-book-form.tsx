"use client";

import { FormEvent, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { TextAreaField, TextField } from "@/components/ui/text-field";
import { createBook } from "@/features/books/api/books-api";
import { queryKeys } from "@/lib/query/keys";

export function CreateBookForm() {
  const queryClient = useQueryClient();
  const [title, setTitle] = useState("");
  const [subtitle, setSubtitle] = useState("");
  const [description, setDescription] = useState("");
  const [successMessage, setSuccessMessage] = useState("");

  const mutation = useMutation({
    mutationFn: createBook,
    onMutate: () => {
      setSuccessMessage("");
    },
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
        onChange={(event) => setTitle(event.target.value)}
      />
      <TextField label="Subtítulo" value={subtitle} placeholder="Opcional" onChange={(event) => setSubtitle(event.target.value)} />
      <TextAreaField
        label="Descrição"
        value={description}
        rows={4}
        placeholder="Uma frase sobre a ideia central, gênero ou promessa da história."
        onChange={(event) => setDescription(event.target.value)}
      />

      <Button type="submit" disabled={mutation.isPending || !title.trim()} className="mt-1 min-h-11 w-full">
        {mutation.isPending ? "Criando..." : "Criar livro"}
      </Button>

      {successMessage ? <FeedbackMessage variant="success">{successMessage}</FeedbackMessage> : null}

      {mutation.isError ? (
        <FeedbackMessage variant="error">
          Não foi possível criar o livro agora. Verifique se o backend está rodando em localhost:8085.
        </FeedbackMessage>
      ) : null}
    </form>
  );
}
