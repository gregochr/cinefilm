import { useCallback, useEffect, useRef, useState } from 'react';
import { SIGNED_IN_EVENT } from '../api/client.js';
import { adminApi } from '../api/resources.js';
import { LOG_PAGE_SIZE, logQuery } from './playbackLog.js';

const LOADING = {
  rows: null,
  pages: 0,
  total: 0,
  error: false,
  moreError: false,
  loadingMore: false,
};

/**
 * The Admin "Playback log": one page of playbacks at a time for the chosen filter ('all', 'films' or 'problems'),
 * read from the server, which does the filtering (the browser never filters). `more()` appends the next page
 * until the pages read cover `total` (`pages` counts the pages read, so a row that moved between pages cannot skip
 * one, and a failed "show more" counts as none). More is decided from that page offset, never from the rows
 * returned: a page can hold fewer than 50 rows when a stored report is unreadable (the server leaves it out of
 * `playbacks` but counts it in `total`), and a play that started meanwhile pushes rows down a page, so the rows
 * shown can stay under `total`. Only the newest request may change what is shown: a filter switch or a retry
 * abandons the older request (it is aborted, and an answer that still arrives is ignored).
 *
 * `rows` is null until the first page of the filter arrives (or has failed: `error`). `moreError` is a failed
 * "show more", which keeps the rows already shown.
 */
export function usePlaybackLog() {
  const [filter, setFilterState] = useState('all');
  const [state, setState] = useState(LOADING);
  const latest = useRef({ id: 0, controller: null });

  const fetchPage = useCallback((filterKey, page, append) => {
    latest.current.controller?.abort();
    const controller = new AbortController();
    const id = latest.current.id + 1;
    latest.current = { id, controller };
    const current = () => latest.current.id === id;
    adminApi
      .playbackLog(logQuery(filterKey, page), { signal: controller.signal })
      .then((answer) => {
        if (!current()) return;
        setState((previous) => {
          // A play that starts while the log is open pushes later rows down a page: a row already shown is not added twice.
          const shown = append ? previous.rows : [];
          const known = new Set(shown.map((row) => row.playbackId));
          const added = answer.playbacks.filter((row) => !known.has(row.playbackId));
          return {
            ...LOADING,
            rows: [...shown, ...added],
            pages: append ? previous.pages + 1 : 1,
            total: answer.total,
          };
        });
      })
      .catch(() => {
        if (!current()) return;
        setState((previous) =>
          append
            ? { ...previous, loadingMore: false, moreError: true }
            : { ...LOADING, error: true },
        );
      });
  }, []);

  useEffect(() => {
    fetchPage('all', 0, false);
    const request = latest;
    return () => {
      // The newest request is abandoned with the card: its answer must not set state on an unmounted hook.
      request.current.controller?.abort();
      request.current = { id: request.current.id + 1, controller: null };
    };
  }, [fetchPage]);

  // A sign-in after a session expiry (which can have failed the first read with a 401) reads the first page again,
  // for the filter then selected, as `useResource` does for the cards built on it.
  const filterRef = useRef(filter);
  useEffect(() => {
    filterRef.current = filter;
  });
  useEffect(() => {
    const onSignedIn = () => {
      setState(LOADING);
      fetchPage(filterRef.current, 0, false);
    };
    window.addEventListener(SIGNED_IN_EVENT, onSignedIn);
    return () => window.removeEventListener(SIGNED_IN_EVENT, onSignedIn);
  }, [fetchPage]);

  const setFilter = useCallback(
    (key) => {
      setFilterState(key);
      setState(LOADING);
      fetchPage(key, 0, false);
    },
    [fetchPage],
  );

  const reload = useCallback(() => {
    setState(LOADING);
    fetchPage(filter, 0, false);
  }, [fetchPage, filter]);

  const more = useCallback(() => {
    if (state.loadingMore) return;
    setState((previous) => ({ ...previous, loadingMore: true, moreError: false }));
    fetchPage(filter, state.pages, true);
  }, [fetchPage, filter, state.pages, state.loadingMore]);

  return {
    filter,
    setFilter,
    rows: state.rows,
    total: state.total,
    loading: state.rows === null && !state.error,
    error: state.error,
    moreError: state.moreError,
    loadingMore: state.loadingMore,
    hasMore: state.rows !== null && state.pages * LOG_PAGE_SIZE < state.total,
    more,
    reload,
  };
}
