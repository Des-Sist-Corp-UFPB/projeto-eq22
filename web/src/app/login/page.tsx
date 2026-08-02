import type { Metadata } from "next";
import { Suspense } from "react";
import { LoginForm } from "@/features/auth/components/login-form";
import { ManuscriptArtwork } from "@/features/auth/components/manuscript-artwork";

export const metadata: Metadata = {
  title: "Entrar — IWrite",
};

export default function LoginPage() {
  return (
    <main className="min-h-screen bg-[#f7f7f2] text-zinc-950 lg:grid lg:grid-cols-2">
      <section className="flex flex-col justify-center gap-6 px-5 py-10 md:px-10 lg:px-14 lg:py-16">
        <p className="text-sm font-semibold uppercase tracking-[0.2em] text-zinc-500">IWrite</p>
        <div className="grid gap-3">
          <h1 className="text-3xl font-semibold tracking-tight text-zinc-950 md:text-4xl lg:text-5xl">
            Dê forma à sua história.
          </h1>
          <p className="max-w-md text-base leading-7 text-zinc-600">
            Organize manuscritos, cenas, personagens e ideias em um espaço criado para escrever.
          </p>
        </div>
        {/* Decorative, and the first thing to go when the viewport gets short: on mobile the form
            is what the reader came for. */}
        <ManuscriptArtwork className="hidden lg:block" />
      </section>

      <section className="flex items-center justify-center border-t border-zinc-200 bg-white px-5 py-10 md:px-10 lg:border-l lg:border-t-0 lg:py-16">
        <div className="grid w-full max-w-sm gap-6">
          <div className="grid gap-2">
            <h2 className="text-2xl font-semibold tracking-tight text-zinc-950">Bem-vindo de volta</h2>
            <p className="text-sm leading-6 text-zinc-600">Entre para continuar de onde parou.</p>
          </div>

          {/* useSearchParams needs a boundary, otherwise the route cannot be statically rendered. */}
          <Suspense fallback={null}>
            <LoginForm />
          </Suspense>

          <p className="text-xs leading-5 text-zinc-500">
            Seus manuscritos permanecem privados em seu espaço de trabalho.
          </p>
        </div>
      </section>
    </main>
  );
}
