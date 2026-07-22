import { downloadFile } from "@/lib/api/download-file";

export type ExportFormat = "txt" | "md" | "docx";

export type ManuscriptExportOptions = {
  format: ExportFormat;
  includeSceneTitles: boolean;
  includeEmptyScenes: boolean;
};

export type NotebookExportOptions = {
  format: ExportFormat;
  includeOpen: boolean;
  includeResolved: boolean;
};

const fallbackFileNames: Record<ExportFormat, string> = {
  txt: "manuscrito.txt",
  md: "manuscrito.md",
  docx: "manuscrito.docx",
};

const notebookFallbackFileNames: Record<ExportFormat, string> = {
  txt: "caderno.txt",
  md: "caderno.md",
  docx: "caderno.docx",
};

export async function downloadBookExport(bookId: string, options: ManuscriptExportOptions) {
  const params = new URLSearchParams({
    format: options.format,
    includeSceneTitles: String(options.includeSceneTitles),
    includeEmptyScenes: String(options.includeEmptyScenes),
  });

  return downloadFile({
    path: `/api/books/${bookId}/exports/manuscript?${params.toString()}`,
    fallbackFileName: fallbackFileNames[options.format],
  });
}

export async function downloadNotebookExport(bookId: string, options: NotebookExportOptions) {
  const params = new URLSearchParams({
    format: options.format,
    includeOpen: String(options.includeOpen),
    includeResolved: String(options.includeResolved),
  });

  return downloadFile({
    path: `/api/books/${bookId}/exports/notebook?${params.toString()}`,
    fallbackFileName: notebookFallbackFileNames[options.format],
  });
}
