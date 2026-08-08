// @vitest-environment node
import { createServer, request as httpRequest } from "node:http";
import { afterAll, beforeAll, describe, expect, test } from "vitest";
import { applyClientAddress, requestListener } from "./server.mjs";

/**
 * Drives the real listener the production server uses, over a real socket, so what is asserted is
 * the headers Next would actually receive - not a re-implementation of the sanitising step.
 */
let server;
let baseUrl;

beforeAll(async () => {
  server = createServer(
    requestListener((req, res) => {
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify(req.headers));
    }),
  );
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  baseUrl = `http://127.0.0.1:${server.address().port}`;
});

afterAll(() => new Promise((resolve) => server.close(resolve)));

/** Sends `headers` through a real request and returns what the handler behind the listener saw. */
function headersSeenByNext(headers) {
  const { hostname, port } = new URL(baseUrl);
  return new Promise((resolve, reject) => {
    // node:http rather than fetch: fetch filters some request headers, and these tests exist
    // precisely to send the ones a hostile client would.
    const req = httpRequest({ hostname, port, path: "/", headers }, (res) => {
      let body = "";
      res.setEncoding("utf8");
      res.on("data", (chunk) => (body += chunk));
      res.on("end", () => resolve(JSON.parse(body)));
    });
    req.on("error", reject);
    req.end();
  });
}

describe("forwarding headers supplied by the client", () => {
  test("Forwarded: for=<forjado> é removido antes de chegar ao Next", async () => {
    const seen = await headersSeenByNext({ Forwarded: 'for="203.0.113.9"' });

    expect(seen.forwarded).toBeUndefined();
    expect(seen["x-forwarded-for"]).toBe("127.0.0.1");
  });

  test("X-Forwarded-For forjado é sobrescrito pelo endereço do socket", async () => {
    const seen = await headersSeenByNext({ "X-Forwarded-For": "203.0.113.9" });

    expect(seen["x-forwarded-for"]).toBe("127.0.0.1");
    expect(seen["x-forwarded-for"]).not.toContain("203.0.113.9");
  });

  test("os dois enviados juntos não controlam o endereço final", async () => {
    const seen = await headersSeenByNext({
      Forwarded: 'for="198.51.100.7";proto=https',
      "X-Forwarded-For": "203.0.113.9, 192.0.2.1",
    });

    expect(seen.forwarded).toBeUndefined();
    expect(seen["x-forwarded-for"]).toBe("127.0.0.1");
  });

  test("os demais cabeçalhos são preservados", async () => {
    const seen = await headersSeenByNext({
      Forwarded: 'for="203.0.113.9"',
      Cookie: "JSESSIONID=abc",
      "Accept-Language": "pt-BR",
      "X-XSRF-TOKEN": "token-csrf",
    });

    expect(seen.cookie).toBe("JSESSIONID=abc");
    expect(seen["accept-language"]).toBe("pt-BR");
    expect(seen["x-xsrf-token"]).toBe("token-csrf");
  });

  test("25 tentativas variando Forwarded chegam sempre com a mesma origem", async () => {
    const addresses = new Set();

    for (let attempt = 0; attempt < 25; attempt++) {
      const seen = await headersSeenByNext({
        Forwarded: `for="203.0.113.${attempt}"`,
        "X-Forwarded-For": `198.51.100.${attempt}`,
      });
      expect(seen.forwarded).toBeUndefined();
      addresses.add(seen["x-forwarded-for"]);
    }

    // One bucket for all 25: nothing the caller sent moved the address the backend keys on.
    expect([...addresses]).toEqual(["127.0.0.1"]);
  });

  test("socket sem endereço não inventa valor: nenhum cabeçalho de encaminhamento sai daqui", () => {
    const headers = applyClientAddress(
      { forwarded: 'for="203.0.113.9"', "x-forwarded-for": "203.0.113.9", cookie: "JSESSIONID=abc" },
      undefined,
    );

    expect(headers.forwarded).toBeUndefined();
    expect(headers["x-forwarded-for"]).toBeUndefined();
    expect(headers.cookie).toBe("JSESSIONID=abc");
  });
});
