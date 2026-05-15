"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { downloadBookMarkdownExport } from "@/features/export/api/export-api";

type ExportManuscriptButtonProps = {
  bookId: string;
};

export function ExportManuscriptButton({ bookId }: ExportManuscriptButtonProps) {
  const [isExporting, setIsExporting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  async function handleExport() {
    setIsExporting(true);
    setErrorMessage(null);

    try {
      await downloadBookMarkdownExport(bookId);
    } catch {
      setErrorMessage("Nao foi possivel exportar o manuscrito agora. Tente novamente.");
    } finally {
      setIsExporting(false);
    }
  }

  return (
    <div className="relative">
      <Button type="button" size="sm" variant="secondary" disabled={isExporting} onClick={handleExport}>
        {isExporting ? "Exportando..." : "Exportar manuscrito"}
      </Button>
      {errorMessage ? (
        <FeedbackMessage variant="error" className="absolute right-0 top-10 z-10 w-72 shadow-sm">
          {errorMessage}
        </FeedbackMessage>
      ) : null}
    </div>
  );
}
