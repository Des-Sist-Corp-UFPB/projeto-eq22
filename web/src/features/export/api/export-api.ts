const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8085";

export type BookExportFormat = "markdown" | "docx";

export type BookExportOptions = {
  format: BookExportFormat;
  includeSceneTitles: boolean;
  includeEmptyScenes: boolean;
};

const EXPORT_FORMAT_CONFIG: Record<BookExportFormat, { path: string; fallbackFileName: string }> = {
  markdown: { path: "export/markdown", fallbackFileName: "manuscrito.md" },
  docx: { path: "export/docx", fallbackFileName: "manuscrito.docx" },
};

export async function downloadBookExport(bookId: string, options: BookExportOptions) {
  const formatConfig = EXPORT_FORMAT_CONFIG[options.format];
  const params = new URLSearchParams({
    includeSceneTitles: String(options.includeSceneTitles),
    includeEmptyScenes: String(options.includeEmptyScenes),
  });
  const response = await fetch(`${API_URL}/api/books/${bookId}/${formatConfig.path}?${params.toString()}`);

  if (!response.ok) {
    throw new Error(await readExportErrorMessage(response));
  }

  const blob = await response.blob();
  const fileName = getFileNameFromContentDisposition(response.headers.get("content-disposition")) ?? formatConfig.fallbackFileName;
  const url = window.URL.createObjectURL(blob);
  const anchor = document.createElement("a");

  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.URL.revokeObjectURL(url);
}

function getFileNameFromContentDisposition(contentDisposition: string | null) {
  if (!contentDisposition) {
    return null;
  }

  const fileNameMatch = contentDisposition.match(/filename="([^"]+)"/i) ?? contentDisposition.match(/filename=([^;]+)/i);
  return fileNameMatch?.[1]?.trim() || null;
}

async function readExportErrorMessage(response: Response) {
  try {
    const data = (await response.json()) as { messages?: string[]; error?: string };
    return data.messages?.join(", ") ?? data.error ?? `HTTP ${response.status}`;
  } catch {
    return `HTTP ${response.status}`;
  }
}
