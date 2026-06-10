import { beforeEach, describe, expect, test, vi } from "vitest";
import { downloadFile, getFileNameFromContentDisposition } from "@/lib/api/download-file";

describe("downloadFile", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    document.body.innerHTML = "";
    Object.defineProperty(window.URL, "createObjectURL", {
      configurable: true,
      value: vi.fn(() => "blob:export"),
    });
    Object.defineProperty(window.URL, "revokeObjectURL", {
      configurable: true,
      value: vi.fn(),
    });
  });

  test("parseia filename do Content-Disposition", () => {
    expect(getFileNameFromContentDisposition('attachment; filename="caderno-livro.txt"')).toBe("caderno-livro.txt");
    expect(getFileNameFromContentDisposition("attachment; filename=livro.md")).toBe("livro.md");
    expect(getFileNameFromContentDisposition("attachment; filename*=UTF-8''caderno%20livro.docx")).toBe(
      "caderno livro.docx"
    );
  });

  test("baixa blob com nome do header e revoga object URL", async () => {
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    const appendSpy = vi.spyOn(document.body, "appendChild");
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response("conteudo", {
        status: 200,
        headers: { "content-disposition": 'attachment; filename="livro-exportado.md"' },
      })
    );

    await downloadFile({ path: "/api/export", fallbackFileName: "fallback.md" });

    expect(fetchMock).toHaveBeenCalledWith("http://localhost:8085/api/export");
    expect(window.URL.createObjectURL).toHaveBeenCalledWith(expect.any(Blob));
    expect(appendSpy).toHaveBeenCalledWith(expect.objectContaining({ download: "livro-exportado.md" }));
    expect(clickSpy).toHaveBeenCalledTimes(1);
    expect(window.URL.revokeObjectURL).toHaveBeenCalledWith("blob:export");
  });
});
