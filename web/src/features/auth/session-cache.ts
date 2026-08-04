import { hashKey, type QueryClient } from "@tanstack/react-query";
import { SESSION_QUERY_KEY } from "@/features/auth/session-query-key";

function isDomainQuery(queryKey: readonly unknown[]) {
  return hashKey(queryKey) !== hashKey(SESSION_QUERY_KEY);
}

/** Every cache entry fetched under a session that is ending, the session key itself excluded so the
 *  route guard can redirect from its data instead of racing a fresh /api/auth/me. Not enumerated by
 *  name, so no current or future domain query can be missed.
 *
 *  Uses `query.reset()` rather than `client.removeQueries()`: removing deletes the Query object from
 *  the cache outright, and a component that stays mounted through the swap (rather than genuinely
 *  unmounting and remounting - which React is free to skip entirely if the whole reconciliation
 *  settles inside one batch, as it does whenever /api/auth/me resolves fast) keeps its observer
 *  pointed at that now-orphaned object forever, showing the old tenant's last data with nothing left
 *  to ever refetch it. `reset()` clears the same query's state in place, so the observer that is
 *  still watching it is notified immediately - no remount required - and the query stays in the
 *  cache for refetchActiveDomainQueries to find afterwards. */
export function purgeAuthenticatedCaches(client: QueryClient): void {
  client
    .getQueryCache()
    .findAll({ predicate: (query) => isDomainQuery(query.queryKey) })
    .forEach((query) => query.reset());
  client.getMutationCache().clear();
}

/** The other half of purgeAuthenticatedCaches: once reconciliation has landed on a session worth
 *  keeping (a real identity, not a logout), whatever domain queries are still actively observed need
 *  to actually reload for it. Deliberately not automatic — purging ahead of a session ending in
 *  logout must not spend a retried, doomed-to-401 refetch on data about to be abandoned anyway. */
export async function refetchActiveDomainQueries(client: QueryClient): Promise<void> {
  await client.refetchQueries({ type: "active", predicate: (query) => isDomainQuery(query.queryKey) });
}

/** The instant a reconciliation actually discarded the cache, keyed per QueryClient so tests that
 *  create several clients never share state. A mutation already in flight at that point belongs to
 *  the identity being discarded, however long it takes to settle. */
const reconciliationCutoffs = new WeakMap<QueryClient, number>();

export function markReconciliationStart(client: QueryClient): void {
  reconciliationCutoffs.set(client, Date.now());
}

/** A mutation started before the last reconciliation cutoff can only be a straggler from the
 *  identity that reconciliation just discarded, no matter what it wrote on its own way out. */
export function isStaleMutation(client: QueryClient, submittedAt: number): boolean {
  const cutoff = reconciliationCutoffs.get(client);
  return cutoff !== undefined && submittedAt < cutoff;
}
