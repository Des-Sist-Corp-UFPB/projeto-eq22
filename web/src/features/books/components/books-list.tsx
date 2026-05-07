"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Badge } from "@/components/ui/badge";
import { EmptyState } from "@/components/ui/empty-state";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { LoadingState } from "@/components/ui/feedback";
import { listBooks } from "@/features/books/api/books-api";
import type { BookStatus } from "@/features/books/types";
import { queryKeys } from "@/lib/query/keys";

const statusLabels: Record<BookStatus, string> = {
  PLANNING: "Planejamento",
  WRITING: "Escrita",
  REVISING: "Revisão",
  FINISHED: "Finalizado",
  ARCHIVED: "Arquivado",
};

export function BooksList() {
  const query = useQuery({
    queryKey: queryKeys.books,
    queryFn: listBooks,
  });

  if (query.isLoading) {
    return <LoadingState label="Carregando livros..." />;
  }

  if (query.isError) {
    return (
      <div className="grid gap-3">
        <EmptyState
          title="Não foi possível carregar a biblioteca"
          description="Nenhum livro foi carregado porque a API não respondeu como esperado."
        />
        <FeedbackMessage variant="error">
          Verifique se o backend está rodando em localhost:8085 e tente novamente.
        </FeedbackMessage>
      </div>
    );
  }

  if (!query.data?.length) {
    return (
      <EmptyState
        title="Sua biblioteca ainda está vazia"
        description="Crie seu primeiro livro pelo formulário ao lado. Ele aparecerá aqui como um card pronto para abrir o workspace."
      />
    );
  }

  return (
    <div className="grid gap-4 sm:grid-cols-2">
      {query.data.map((book) => (
        <Link
          key={book.id}
          href={`/books/${book.id}`}
          className="group grid min-h-48 content-between rounded-lg border border-zinc-200 bg-white p-5 shadow-sm shadow-zinc-200/60 transition hover:-translate-y-0.5 hover:border-zinc-300 hover:shadow-md focus:outline-none focus:ring-2 focus:ring-zinc-800 focus:ring-offset-2"
        >
          <div className="grid gap-4">
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0">
                <h2 className="line-clamp-2 text-xl font-semibold leading-7 text-zinc-950">{book.title}</h2>
                {book.subtitle ? <p className="mt-1 line-clamp-1 text-sm text-zinc-600">{book.subtitle}</p> : null}
              </div>
              <Badge className="shrink-0">{statusLabels[book.status]}</Badge>
            </div>

            {book.description ? (
              <p className="line-clamp-3 text-sm leading-6 text-zinc-600">{book.description}</p>
            ) : (
              <p className="text-sm leading-6 text-zinc-500">Sem descrição ainda.</p>
            )}
          </div>

          <div className="mt-6 flex items-center justify-between border-t border-zinc-100 pt-4 text-sm">
            <span className="text-zinc-500">Workspace</span>
            <span className="font-medium text-zinc-900 transition group-hover:translate-x-1">Abrir</span>
          </div>
        </Link>
      ))}
    </div>
  );
}
