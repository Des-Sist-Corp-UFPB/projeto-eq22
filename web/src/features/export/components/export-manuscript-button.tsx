"use client";

import { useMutation } from "@tanstack/react-query";
import { useState } from "react";
import { downloadBookExport, type ExportFormat } from "@/features/export/api/export-api";
import { ExportOptionsPopover } from "@/features/export/components/export-options-popover";
import { trackEvent } from "@/lib/analytics/analytics";
import { ApiError } from "@/lib/api/client";

const EXPORT_FAILED_MESSAGE = "Nao foi possivel exportar o manuscrito agora. Tente novamente.";

type ExportManuscriptButtonProps = {
  bookId: string;
};

export function ExportManuscriptButton({ bookId }: ExportManuscriptButtonProps) {
  const [isOptionsOpen, setIsOptionsOpen] = useState(false);
  const [format, setFormat] = useState<ExportFormat>("md");
  const [includeSceneTitles, setIncludeSceneTitles] = useState(false);
  const [includeEmptyScenes, setIncludeEmptyScenes] = useState(false);

  // A mutation, not a plain async call: this is what routes a failed export through the same
  // status-401 handling every other request in the app gets (QueryProvider's mutation cache),
  // instead of a download-specific expiration path.
  const exportMutation = useMutation({
    mutationFn: () => downloadBookExport(bookId, { format, includeSceneTitles, includeEmptyScenes }),
    onSuccess: () => {
      trackEvent({ name: "book_exported", data: { target: "manuscript", format } });
      setIsOptionsOpen(false);
    },
  });

  // A 401 already ends the session and redirects to login through the global handler; showing a
  // export-failed message on top of that redirect would just be confusing.
  const errorMessage =
    exportMutation.isError && !(exportMutation.error instanceof ApiError && exportMutation.error.status === 401)
      ? EXPORT_FAILED_MESSAGE
      : null;

  return (
    <ExportOptionsPopover
      triggerLabel="Exportar manuscrito"
      title="Exportar manuscrito"
      submitLabel="Baixar manuscrito"
      pendingLabel="Exportando..."
      isOpen={isOptionsOpen}
      isPending={exportMutation.isPending}
      format={format}
      formatGroupName="manuscript-export-format"
      errorMessage={errorMessage}
      onToggle={() => {
          exportMutation.reset();
          setIsOptionsOpen((current) => !current);
      }}
      onFormatChange={setFormat}
      onSubmit={() => exportMutation.mutate()}
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
