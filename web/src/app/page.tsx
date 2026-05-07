import { BooksList } from "@/features/books/components/books-list";
import { CreateBookForm } from "@/features/books/components/create-book-form";

export default function HomePage() {
  return (
    <main className="min-h-screen bg-[#f7f7f2] px-5 py-8 text-zinc-950 md:px-8 md:py-12">
      <div className="mx-auto grid w-full max-w-6xl gap-8 lg:grid-cols-[1fr_360px]">
        <section className="grid content-start gap-6">
          <header className="grid gap-3">
            <div className="inline-flex w-fit rounded-full border border-zinc-200 bg-white px-3 py-1 text-xs font-medium text-zinc-600 shadow-sm">
              Biblioteca
            </div>
            <div className="grid gap-2">
              <h1 className="text-4xl font-semibold tracking-normal text-zinc-950 md:text-5xl">IWrite</h1>
              <p className="max-w-2xl text-base leading-7 text-zinc-600">
                Organize seus livros em andamento e entre no workspace quando for hora de escrever.
              </p>
            </div>
          </header>
          <BooksList />
        </section>

        <aside className="content-start lg:sticky lg:top-8">
          <div className="grid gap-3">
            <div>
              <h2 className="text-lg font-semibold text-zinc-950">Novo livro</h2>
              <p className="text-sm text-zinc-600">Comece com um título. O restante pode nascer depois.</p>
            </div>
            <CreateBookForm />
          </div>
        </aside>
      </div>
    </main>
  );
}
