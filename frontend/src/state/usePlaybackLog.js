import { useCallback, useEffect, useRef, useState } from 'react';
import { adminApi } from '../api/resources.js';
import { logQuery } from './playbackLog.js';

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
 * until every row is shown (`pages` counts the pages read, so a row that moved between pages cannot skip one). Only the newest request may change what is shown: a filter switch or a retry abandons
 * the older request (it is aborted, and an answer that still arrives is ignored).
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
          const rows = [...shown, ...answer.playbacks.filter((row) => !known.has(row.playbackId))];
          return { ...LOADING, rows, pages: append ? previous.pages + 1 : 1, total: answer.total };
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
    hasMore: state.rows !== null && state.rows.length < state.total,
    more,
    reload,
  };
}
