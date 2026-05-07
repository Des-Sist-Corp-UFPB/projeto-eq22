import { FeedbackMessage } from "@/components/ui/feedback-message";
import { Textarea } from "@/components/ui/textarea";

type SceneContentEditorProps = {
  contentText: string;
  wordCount: number;
  isSuccess: boolean;
  isError: boolean;
  onContentChange: (contentText: string) => void;
};

export function SceneContentEditor({
  contentText,
  wordCount,
  isSuccess,
  isError,
  onContentChange,
}: SceneContentEditorProps) {
  return (
    <div className="grid min-h-0 gap-4 bg-white px-4 py-5 md:px-6">
      <div className="mx-auto grid w-full max-w-4xl gap-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="text-sm font-semibold text-zinc-950">Conteúdo textual</h2>
            <p className="text-xs text-zinc-500">Salvamento manual. O contador é atualizado com o retorno do backend.</p>
          </div>
          <span className="text-sm text-zinc-600">Word count oficial: {wordCount}</span>
        </div>

        <Textarea
          value={contentText}
          onChange={(event) => onContentChange(event.target.value)}
          placeholder="Escreva a cena aqui..."
          className="min-h-[62vh] resize-y rounded-lg border-zinc-200 bg-[#fffefb] px-5 py-5 text-[17px] leading-8 text-zinc-900 shadow-inner shadow-zinc-100 focus:border-zinc-800 focus:ring-2 focus:ring-zinc-200 md:px-7 md:py-6"
        />

        <div className="min-h-10">
          {isSuccess ? (
            <FeedbackMessage variant="success">Conteúdo salvo. Word count atualizado pelo backend.</FeedbackMessage>
          ) : null}

          {isError ? (
            <FeedbackMessage variant="error">
              Não foi possível salvar o conteúdo agora. Verifique se o backend está rodando e tente novamente.
            </FeedbackMessage>
          ) : null}
        </div>
      </div>
    </div>
  );
}
