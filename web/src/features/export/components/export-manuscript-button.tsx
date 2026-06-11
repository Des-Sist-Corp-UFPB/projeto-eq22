"use client";

import { useState } from "react";
import { downloadBookExport, type ExportFormat } from "@/features/export/api/export-api";
import { ExportOptionsPopover } from "@/features/export/components/export-options-popover";

type ExportManuscriptButtonProps = {
  bookId: string;
};

export function ExportManuscriptButton({ bookId }: ExportManuscriptButtonProps) {
  const [isOptionsOpen, setIsOptionsOpen] = useState(false);
  const [format, setFormat] = useState<ExportFormat>("md");
  const [includeSceneTitles, setIncludeSceneTitles] = useState(false);
  const [includeEmptyScenes, setIncludeEmptyScenes] = useState(false);
  const [isExporting, setIsExporting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  async function handleExport() {
    setIsExporting(true);
    setErrorMessage(null);

    try {
      await downloadBookExport(bookId, { format, includeSceneTitles, includeEmptyScenes });
      setIsOptionsOpen(false);
    } catch {
      setErrorMessage("Nao foi possivel exportar o manuscrito agora. Tente novamente.");
    } finally {
      setIsExporting(false);
    }
  }

  return (
    <ExportOptionsPopover
      triggerLabel="Exportar manuscrito"
      title="Exportar manuscrito"
      submitLabel="Baixar manuscrito"
      pendingLabel="Exportando..."
      isOpen={isOptionsOpen}
      isPending={isExporting}
      format={format}
      formatGroupName="manuscript-export-format"
      errorMessage={errorMessage}
      onToggle={() => {
          setErrorMessage(null);
          setIsOptionsOpen((current) => !current);
      }}
      onFormatChange={setFormat}
      onSubmit={handleExport}
    >
      <label className="flex items-start gap-3 text-sm text-zinc-700">
        <input
          type="checkbox"
          className="mt-1"
          checked={includeSceneTitles}
          onChange={(event) => setIncludeSceneTitles(event.target.checked)}
        />
        <span>
          <span className="block font-medium text-zinc-950">Incluir titulos das cenas</span>
          <span className="block text-xs text-zinc-500">Adiciona um titulo antes de cada cena exportada.</span>
        </span>
      </label>

      <label className="flex items-start gap-3 text-sm text-zinc-700">
        <input
          type="checkbox"
          className="mt-1"
          checked={includeEmptyScenes}
          onChange={(event) => setIncludeEmptyScenes(event.target.checked)}
        />
        <span>
          <span className="block font-medium text-zinc-950">Incluir cenas vazias</span>
          <span className="block text-xs text-zinc-500">Cenas sem conteudo aparecem quando os titulos tambem estao incluidos.</span>
        </span>
      </label>
    </ExportOptionsPopover>
  );
}
