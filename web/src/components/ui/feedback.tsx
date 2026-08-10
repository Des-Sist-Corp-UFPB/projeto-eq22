import { FeedbackMessage } from "@/components/ui/feedback-message";

export function LoadingState({ label = "Carregando..." }: { label?: string }) {
  return <div className="rounded-md border border-zinc-200 bg-white p-4 text-sm text-zinc-600">{label}</div>;
}

export function ErrorState({ message }: { message: string }) {
  return (
    <FeedbackMessage variant="error" className="p-4">
      {message}
    </FeedbackMessage>
  );
}
