import { useEffect, useRef } from 'react';
import { adminApi } from '../api/resources.js';
import { useResource } from './useResource.js';

/** How often Admin reads the summary again while the tab is visible, so "Playing now" and the live count stay fresh. */
export const SUMMARY_POLL_MS = 30000;

/**
 * The Admin summary (rail counts, the To do heading and the status strip), read once for the whole layout. It is
 * read again when `settledAt`, `subtitlesVersion` or `changed` changes (a scan, import, subtitle job or applied
 * change finished), every 30 s while `visible`, and once at once when a hidden tab is shown again. `ready` holds the
 * first read until the stamps are known.
 *
 * Each read has its own request: a newer one (a new key, a poll or `reload`) aborts the one before it, so an older
 * answer that arrives late is dropped and never overwrites a newer one (`useResource`). The poll is a quiet
 * `refresh`, so the figures stay on screen while it runs. A failed read leaves `error` set (the data stays) until one
 * answers; `reload` is the retry.
 */
export function useAdminSummary(visible, { settledAt, subtitlesVersion, changed, ready = true }) {
  // Until `ready` (the scan and Plex statuses have each answered once, which settles the stamps) the read is held:
  // the fetcher never answers and no request is made, so a normal load reads the summary once.
  const { data, error, loading, reload, refresh } = useResource(
    `admin-summary:${ready}|${settledAt}|${subtitlesVersion}|${changed}`,
    (options) => (ready ? adminApi.summary(options) : new Promise(() => {})),
  );

  // Every 30 s whatever the last request is doing: `refresh` aborts one still in flight, so a request that never
  // answers cannot stop the poll and requests never pile up.
  useEffect(() => {
    if (!visible || !ready) return undefined;
    const id = setInterval(refresh, SUMMARY_POLL_MS);
    return () => clearInterval(id);
  }, [visible, ready, refresh]);

  // Coming back to a hidden tab reads once at once: the figures may be long out of date.
  const wasHidden = useRef(false);
  useEffect(() => {
    if (!visible) {
      wasHidden.current = true;
    } else if (wasHidden.current) {
      wasHidden.current = false;
      refresh();
    }
  }, [visible, refresh]);

  return { summary: data, loading, error, reload };
}
