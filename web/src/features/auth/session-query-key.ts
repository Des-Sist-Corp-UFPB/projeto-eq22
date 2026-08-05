/** One cache entry for the whole app, so /api/auth/me is requested once however many components ask.
 *  Its own module so cache-mechanics code (query-provider, session-sync) never has to import
 *  session.ts just for this constant — that back-edge is what would make the three files a cycle. */
export const SESSION_QUERY_KEY = ["auth", "session"] as const;
