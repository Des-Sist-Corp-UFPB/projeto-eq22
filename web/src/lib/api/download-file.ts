const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8085";

type DownloadFileOptions = {
  path: string;
  fallbackFileName: string;
};

export async function downloadFile({ path, fallbackFileName }: DownloadFileOptions) {
  const response = await fetch(`${API_URL}${path}`);

  if (!response.ok) {
    throw new Error(await readDownloadErrorMessage(response));
  }

  const blob = await response.blob();
  const fileName = getFileNameFromContentDisposition(response.headers.get("content-disposition")) ?? fallbackFileName;
  const url = window.URL.createObjectURL(blob);
  const anchor = document.createElement("a");

  try {
    anchor.href = url;
    anchor.download = fileName;
    document.body.appendChild(anchor);
    anchor.click();
  } finally {
    anchor.remove();
    window.URL.revokeObjectURL(url);
  }
}

export function getFileNameFromContentDisposition(contentDisposition: string | null) {
  if (!contentDisposition) {
    return null;
  }

  const encodedFileNameMatch = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i);
  if (encodedFileNameMatch?.[1]) {
    return decodeURIComponent(encodedFileNameMatch[1].trim());
  }

  const fileNameMatch = contentDisposition.match(/filename="([^"]+)"/i) ?? contentDisposition.match(/filename=([^;]+)/i);
  return fileNameMatch?.[1]?.trim() || null;
}

async function readDownloadErrorMessage(response: Response) {
  try {
    const data = (await response.json()) as { messages?: string[]; error?: string };
    return data.messages?.join(", ") ?? data.error ?? `HTTP ${response.status}`;
  } catch {
    return `HTTP ${response.status}`;
  }
}
