import { Blob as NodeBlob } from "node:buffer";
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
    const anchor = document.createElement("a");
    const clickMock = vi.fn();
    anchor.click = clickMock;
    const createElementSpy = vi.spyOn(document, "createElement").mockImplementation((tagName, options) => {
      if (tagName.toLowerCase() === "a") {
        return anchor;
      }

      return Document.prototype.createElement.call(document, tagName, options);
    });
    const appendSpy = vi.spyOn(document.body, "appendChild");
    const response = new Response(null, {
      status: 200,
      headers: {
        "content-disposition": 'attachment; filename="livro-exportado.md"',
        "content-type": "text/plain;charset=utf-8",
      },
    });
    vi.spyOn(response, "blob").mockResolvedValue(
      new NodeBlob(["conteudo"], { type: "text/plain;charset=utf-8" }) as Blob
    );
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(response);

    await downloadFile({ path: "/api/export", fallbackFileName: "fallback.md" });

    expect(fetchMock).toHaveBeenCalledWith("http://localhost:8085/api/export");
    expect(window.URL.createObjectURL).toHaveBeenCalledTimes(1);
    const [blob] = vi.mocked(window.URL.createObjectURL).mock.calls[0];
    expect(blob).toBeDefined();
    expect(blob.size).toBe(8);
    expect(blob.type).toBe("text/plain;charset=utf-8");
    expect(typeof blob.arrayBuffer).toBe("function");
    if (typeof blob.text === "function") {
      await expect(blob.text()).resolves.toBe("conteudo");
    }
    expect(createElementSpy).toHaveBeenCalledWith("a");
    expect(appendSpy).toHaveBeenCalledWith(expect.objectContaining({ download: "livro-exportado.md" }));
    expect(clickMock).toHaveBeenCalledTimes(1);
    expect(window.URL.revokeObjectURL).toHaveBeenCalledWith("blob:export");
  });
});
