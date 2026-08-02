import "@testing-library/jest-dom/vitest";
import { cleanup, configure } from "@testing-library/react";
import { afterEach } from "vitest";

// Testing Library waits 1s for its async queries. That is a library default, not this project's
// budget - vitest.config.ts already declares 15s per test.
//
// The workspace suite mounts an 840-line editor with 41 hooks. Its first mount in a file measures
// ~1.5s, and up to ~3.4s while the machine is saturated, so the 1s default expired while the UI was
// still settling correctly: book-workspace.test.tsx failed 4 of 5 runs under CPU load and passed
// 6 of 6 with this budget, nothing else changed.
//
// This waits longer, it does not assert less: every condition must still become true. Deliberately
// well under testTimeout, so a genuinely stuck UI still fails as a Testing Library error with a DOM
// dump instead of a bare test timeout.
configure({ asyncUtilTimeout: 5_000 });

afterEach(() => {
  cleanup();
});
