"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { downloadBookMarkdownExport } from "@/features/export/api/export-api";

type ExportManuscriptButtonProps = {
  bookId: string;
};

export function ExportManuscriptButton({ bookId }: ExportManuscriptButtonProps) {
  const [isOptionsOpen, setIsOptionsOpen] = useState(false);
  const [includeSceneTitles, setIncludeSceneTitles] = useState(false);
  const [includeEmptyScenes, setIncludeEmptyScenes] = useState(false);
  const [isExporting, setIsExporting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  async function handleExport() {
    setIsExporting(true);
    setErrorMessage(null);

    try {
      await downloadBookMarkdownExport(bookId, { includeSceneTitles, includeEmptyScenes });
      setIsOptionsOpen(false);
    } catch {
      setErrorMessage("Nao foi possivel exportar o manuscrito agora. Tente novamente.");
    } finally {
      setIsExporting(false);
    }
  }

  return (
    <div className="relative">
      <Button
        type="button"
        size="sm"
        variant="secondary"
        aria-expanded={isOptionsOpen}
        onClick={() => {
          setErrorMessage(null);
          setIsOptionsOpen((current) => !current);
        }}
      >
        Exportar manuscrito
      </Button>

      {isOptionsOpen ? (
        <div className="absolute right-0 top-10 z-10 w-80 rounded-md border border-zinc-200 bg-white p-4 shadow-lg shadow-zinc-200/80">
          <div className="grid gap-3">
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

            <Button type="button" size="sm" disabled={isExporting} onClick={handleExport}>
              {isExporting ? "Exportando..." : "Baixar Markdown"}
            </Button>
          </div>

          {errorMessage ? (
            <FeedbackMessage variant="error" className="mt-3">
              {errorMessage}
            </FeedbackMessage>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
