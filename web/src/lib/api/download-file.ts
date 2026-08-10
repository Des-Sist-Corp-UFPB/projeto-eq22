import { ApiError, readErrorMessage } from "@/lib/api/client";

type DownloadFileOptions = {
  path: string;
  fallbackFileName: string;
};

export async function downloadFile({ path, fallbackFileName }: DownloadFileOptions) {
  // Same-origin relative path, so the session cookie is sent with the download too.
  const response = await fetch(path, { credentials: "same-origin" });

  if (!response.ok) {
    // ApiError, not a plain Error: this is a plain fetch outside React Query's caches, so a caller
    // wired through useMutation is what lets the shared status-401 handling in QueryProvider see
    // this failure and end the session, same as every other request in the app.
    throw new ApiError(await readErrorMessage(response), response.status);
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
