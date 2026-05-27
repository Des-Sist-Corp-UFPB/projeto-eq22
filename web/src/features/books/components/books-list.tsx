"use client";

import { useQuery } from "@tanstack/react-query";
import { EmptyState } from "@/components/ui/empty-state";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { LoadingState } from "@/components/ui/feedback";
import { listBooks } from "@/features/books/api/books-api";
import { BookCard } from "@/features/books/components/book-card";
import { sortBooksForLibrary } from "@/features/books/utils/sort-books";
import { queryKeys } from "@/lib/query/keys";

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

  const books = sortBooksForLibrary(query.data);

  return (
    <div className="grid gap-4 sm:grid-cols-2">
      {books.map((book) => (
        <BookCard key={book.id} book={book} />
      ))}
    </div>
  );
}
