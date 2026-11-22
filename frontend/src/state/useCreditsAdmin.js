import { useCallback, useEffect, useRef, useState } from 'react';
import { adminApi } from '../api/resources.js';
import { pageAfterRead } from './creditsAdmin.js';
import { useResource } from './useResource.js';

/**
 * The End credits list: the page of `filter`'s rows. The page number is this hook's state (not the address) and goes
 * back to 0 when the filter changes. `signInCount` (from `useSignInRecovery`) is part of the request, so signing in
 * after a 401 reads the list again.
 */
export function useCreditsAdmin(filter, signInCount) {
  const [at, setAt] = useState({ filter, page: 0 });
  // A new filter starts at page 0: the stored page is reset, so coming back to an old filter doesn't reopen its page.
  if (at.filter !== filter) setAt({ filter, page: 0 });
  const page = at.filter === filter ? at.page : 0;
  const read = useResource(`admin-credits:${filter}:${page}:${signInCount}`, (options) =>
    adminApi.credits(filter, page, options),
  );
  const { data, loading } = read;
  const setPage = useCallback((next) => setAt({ filter, page: next }), [filter]);

  // A page that came back empty while the filter still has films steps back one. Only an answer for this very page
  // counts: the render after the step still holds the old page's data until the next read starts.
  const answered = data && !loading && data.page === page;
  const stepBackTo = answered ? pageAfterRead(page, data.items.length, data.total) : page;
  useEffect(() => {
    if (stepBackTo !== page) setPage(stepBackTo);
  }, [stepBackTo, page, setPage]);

  // Data for another page, or an empty answer about to step back, is not this page's answer: still loading.
  const stale = data != null && data.page !== page;
  // A failed read wins: the old data stays in `read` on failure, and must not keep the screen on its skeleton.
  const waiting = !read.error && (loading || stale || stepBackTo !== page);
  return { ...read, loading: waiting, page, setPage };
}

/**
 * How long after a change of key the counts are read once more. The scan and the Plex import publish their finish
 * events while still holding the library lock, so the marker pass's first try fails and it retries after its
 * `LOCK_WAIT` of 5 s, then runs: 15 s covers the wait and the pass itself.
 */
export const COUNTS_FOLLOW_UP_MS = 15000;

/**
 * The three counts the Library card shows, read from the first page of "Needs a look". `version` is part of the
 * request, so a scan or import finishing reads the counts again. The marker pass runs on a background executor after
 * that finish, so one more quiet read follows `COUNTS_FOLLOW_UP_MS` after any later change of key (cancelled by the
 * next change or by unmount).
 */
export function useCreditsCounts(signInCount, version) {
  const { data, loading, error, reload, refresh } = useResource(
    `admin-credits-counts:${signInCount}:${version}`,
    (options) => adminApi.credits('none', 0, options),
  );
  const first = useRef(true);
  useEffect(() => {
    if (first.current) {
      first.current = false;
      return undefined;
    }
    const id = setTimeout(refresh, COUNTS_FOLLOW_UP_MS);
    return () => clearTimeout(id);
  }, [signInCount, version, refresh]);
  return { counts: data?.counts ?? null, loading, error, reload };
}
