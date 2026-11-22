import { useCallback, useEffect, useState } from 'react';
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

  return { ...read, page, setPage };
}

/** The three counts the Library card shows, read from the first page of "Needs a look". */
export function useCreditsCounts(signInCount) {
  const { data, loading, error, reload } = useResource(
    `admin-credits-counts:${signInCount}`,
    (options) => adminApi.credits('none', 0, options),
  );
  return { counts: data?.counts ?? null, loading, error, reload };
}
