export function LoadingState({ label = "Carregando..." }: { label?: string }) {
  return <div className="rounded-md border border-zinc-200 bg-white p-4 text-sm text-zinc-600">{label}</div>;
}

export function ErrorState({ message }: { message: string }) {
  return <div className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-700">{message}</div>;
}
