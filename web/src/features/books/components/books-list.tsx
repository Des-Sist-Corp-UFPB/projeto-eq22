"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { listBooks } from "@/features/books/api/books-api";
import { queryKeys } from "@/lib/query/keys";
import { ErrorState, LoadingState } from "@/components/ui/feedback";

export function BooksList() {
  const query = useQuery({
    queryKey: queryKeys.books,
    queryFn: listBooks,
  });

  if (query.isLoading) {
    return <LoadingState label="Carregando livros..." />;
  }

  if (query.isError) {
    return <ErrorState message={query.error.message} />;
  }

  if (!query.data?.length) {
    return <div className="rounded-md border border-zinc-200 bg-white p-4 text-sm text-zinc-600">Nenhum livro criado.</div>;
  }

  return (
    <div className="grid gap-3">
      {query.data.map((book) => (
        <Link
          key={book.id}
          href={`/books/${book.id}`}
          className="rounded-md border border-zinc-200 bg-white p-4 transition hover:border-zinc-400"
        >
          <div className="flex items-start justify-between gap-4">
            <div>
              <h2 className="text-lg font-semibold text-zinc-950">{book.title}</h2>
              {book.subtitle ? <p className="text-sm text-zinc-600">{book.subtitle}</p> : null}
            </div>
            <span className="rounded-md bg-zinc-100 px-2 py-1 text-xs font-medium text-zinc-700">{book.status}</span>
          </div>
          {book.description ? <p className="mt-3 line-clamp-2 text-sm text-zinc-600">{book.description}</p> : null}
        </Link>
      ))}
    </div>
  );
}
