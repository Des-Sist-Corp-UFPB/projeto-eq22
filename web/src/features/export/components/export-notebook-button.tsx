"use client";

import { useMutation } from "@tanstack/react-query";
import { useState } from "react";
import { downloadNotebookExport, type ExportFormat } from "@/features/export/api/export-api";
import { ExportOptionsPopover } from "@/features/export/components/export-options-popover";
import { trackEvent } from "@/lib/analytics/analytics";
import { ApiError } from "@/lib/api/client";

type ExportNotebookButtonProps = {
  bookId: string;
};

const emptyStatusSelectionMessage = "Selecione pelo menos um tipo de nota para exportar.";
const EXPORT_FAILED_MESSAGE = "Nao foi possivel exportar o caderno agora. Tente novamente.";

export function ExportNotebookButton({ bookId }: ExportNotebookButtonProps) {
  const [isOptionsOpen, setIsOptionsOpen] = useState(false);
  const [format, setFormat] = useState<ExportFormat>("txt");
  const [includeOpen, setIncludeOpen] = useState(true);
  const [includeResolved, setIncludeResolved] = useState(true);
  const [validationMessage, setValidationMessage] = useState<string | null>(null);

  // A mutation, not a plain async call: this is what routes a failed export through the same
  // status-401 handling every other request in the app gets (QueryProvider's mutation cache),
  // instead of a download-specific expiration path.
  const exportMutation = useMutation({
    mutationFn: () => downloadNotebookExport(bookId, { format, includeOpen, includeResolved }),
    onSuccess: () => {
      trackEvent({ name: "book_exported", data: { target: "notebook", format } });
      setIsOptionsOpen(false);
    },
  });

  // A 401 already ends the session and redirects to login through the global handler; showing a
  // export-failed message on top of that redirect would just be confusing.
  const errorMessage =
    exportMutation.isError && !(exportMutation.error instanceof ApiError && exportMutation.error.status === 401)
      ? EXPORT_FAILED_MESSAGE
      : null;

  function handleExport() {
    if (!includeOpen && !includeResolved) {
      setValidationMessage(emptyStatusSelectionMessage);
      return;
    }

    setValidationMessage(null);
    exportMutation.mutate();
  }

  function clearMessages() {
    setValidationMessage(null);
    exportMutation.reset();
  }

  return (
    <ExportOptionsPopover
      triggerLabel="Exportar caderno"
      title="Exportar caderno"
      submitLabel="Baixar caderno"
      pendingLabel="Exportando..."
      description="A exportação inclui todas as categorias do Caderno."
      isOpen={isOptionsOpen}
      isPending={exportMutation.isPending}
      format={format}
      formatGroupName="notebook-export-format"
      validationMessage={validationMessage}
      errorMessage={errorMessage}
      onToggle={() => {
        clearMessages();
        setIsOptionsOpen((current) => !current);
      }}
      onFormatChange={setFormat}
      onSubmit={handleExport}
    >
      <label className="flex items-start gap-3 text-sm text-zinc-700">
        <input
          type="checkbox"
          className="mt-1"
          checked={includeOpen}
          onChange={(event) => {
            clearMessages();
            setIncludeOpen(event.target.checked);
          }}
        />
        <span>
          <span className="block font-medium text-zinc-950">Incluir notas abertas</span>
          <span className="block text-xs text-zinc-500">Exporta notas ainda em andamento.</span>
        </span>
      </label>

      <label className="flex items-start gap-3 text-sm text-zinc-700">
        <input
          type="checkbox"
          className="mt-1"
          checked={includeResolved}
          onChange={(event) => {
            clearMessages();
            setIncludeResolved(event.target.checked);
          }}
        />
        <span>
          <span className="block font-medium text-zinc-950">Incluir notas resolvidas</span>
          <span className="block text-xs text-zinc-500">Mantem o historico de notas ja concluidas.</span>
        </span>
      </label>
    </ExportOptionsPopover>
  );
}
