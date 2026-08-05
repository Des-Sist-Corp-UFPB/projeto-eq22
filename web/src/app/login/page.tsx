import type { Metadata } from "next";
import { LoginForm } from "@/features/auth/components/login-form";
import { ManuscriptArtwork } from "@/features/auth/components/manuscript-artwork";

export const metadata: Metadata = {
  title: "Entrar — IWrite",
};

/**
 * Reads `?reason=expired` on the server and hands it down. Doing it with useSearchParams instead
 * would force this subtree to render on the client, and the form would be missing from the
 * server-rendered HTML - a visible flash of a login page with no login form.
 */
export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<{ reason?: string }>;
}) {
  const { reason } = await searchParams;


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

          <LoginForm expired={reason === "expired"} />

          <p className="text-xs leading-5 text-zinc-500">
            Seus manuscritos permanecem privados em seu espaço de trabalho.
          </p>
        </div>
      </section>
    </main>
  );
}
