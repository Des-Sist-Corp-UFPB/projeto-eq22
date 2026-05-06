import { BooksList } from "@/features/books/components/books-list";
import { CreateBookForm } from "@/features/books/components/create-book-form";

export default function HomePage() {
  return (
    <main className="mx-auto grid min-h-screen w-full max-w-5xl gap-6 px-5 py-8 md:grid-cols-[1fr_320px]">
      <section className="grid content-start gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-zinc-950">IWrite</h1>
          <p className="text-sm text-zinc-600">Livros em andamento</p>
        </div>
        <BooksList />
      </section>
      <aside className="content-start">
        <CreateBookForm />
      </aside>
    </main>
  );
}
