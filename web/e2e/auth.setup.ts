import { expect, test as setup } from "@playwright/test";
import { STORAGE_STATE } from "./storage-state";

/**
 * Logs in once and saves the cookies every spec then reuses. Since authentication landed, an
 * anonymous request gets 401 and the UI bounces to /login, so there is no such thing as an
 * unauthenticated e2e run any more.
 *
 * Credentials come from the same environment variables that seed the stack; there is no default,
 * so a misconfigured run fails here with a clear message instead of failing five specs later.
 */
setup("authenticate as Autor A", async ({ request }) => {
  const password = process.env.IWRITE_DEMO_AUTOR_A_PASSWORD;
  expect(password, "IWRITE_DEMO_AUTOR_A_PASSWORD must be set for the e2e run").toBeTruthy();

  // Mints the XSRF-TOKEN cookie the login itself has to echo back.
  await request.get("/api/auth/csrf");
  const token = (await request.storageState()).cookies.find((cookie) => cookie.name === "XSRF-TOKEN")?.value;
  expect(token, "the backend should have issued an XSRF-TOKEN cookie").toBeTruthy();

  const response = await request.post("/api/auth/login", {
    headers: { "X-XSRF-TOKEN": token! },
    data: { email: "autor-a@iwrite.local", password },
  });
  expect(response.status(), "login should succeed against the seeded demo stack").toBe(200);

  await request.storageState({ path: STORAGE_STATE });
});
