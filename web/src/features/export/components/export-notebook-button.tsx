"use client";

import { useState } from "react";
import { downloadNotebookExport, type ExportFormat } from "@/features/export/api/export-api";
import { ExportOptionsPopover } from "@/features/export/components/export-options-popover";

type ExportNotebookButtonProps = {
  bookId: string;
};

const emptyStatusSelectionMessage = "Selecione pelo menos um tipo de nota para exportar.";

export function ExportNotebookButton({ bookId }: ExportNotebookButtonProps) {
  const [isOptionsOpen, setIsOptionsOpen] = useState(false);
  const [format, setFormat] = useState<ExportFormat>("txt");
  const [includeOpen, setIncludeOpen] = useState(true);
  const [includeResolved, setIncludeResolved] = useState(true);
  const [isExporting, setIsExporting] = useState(false);
  const [validationMessage, setValidationMessage] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  async function handleExport() {
    if (!includeOpen && !includeResolved) {
      setValidationMessage(emptyStatusSelectionMessage);
      return;
    }

    setIsExporting(true);
    setValidationMessage(null);
    setErrorMessage(null);

    try {
      await downloadNotebookExport(bookId, { format, includeOpen, includeResolved });
      setIsOptionsOpen(false);
    } catch {
      setErrorMessage("Nao foi possivel exportar o caderno agora. Tente novamente.");
    } finally {
      setIsExporting(false);
    }
  }

  function clearMessages() {
    setValidationMessage(null);
    setErrorMessage(null);
  }

  return (
    <ExportOptionsPopover
      triggerLabel="Exportar caderno"
      title="Exportar caderno"
      submitLabel="Baixar caderno"
      pendingLabel="Exportando..."
      description="A exportação inclui todas as categorias do Caderno."
      isOpen={isOptionsOpen}
      isPending={isExporting}
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
