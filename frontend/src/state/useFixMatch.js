import { useCallback, useEffect, useRef, useState } from 'react';
import { announceLibraryChanged } from '../api/client.js';
import { adminApi } from '../api/resources.js';
import { matchProblem, parseYear } from './fixMatch.js';

/**
 * The Fix match panel for one film: searches TMDB (once on open with the film's title, then on demand), keeps the
 * picked result and applies it. `onApplied` runs after a successful apply.
 */
export function useFixMatch(filmId, initialQuery, onApplied) {
  const [query, setQuery] = useState(initialQuery);
  const [year, setYear] = useState('');
  const [search, setSearch] = useState({ status: 'loading', results: [], problem: null });
  const [picked, setPicked] = useState(null);
  const [applying, setApplying] = useState(false);
  const [applyProblem, setApplyProblem] = useState(null);
  const latest = useRef(0);
  const controller = useRef(null);
  const shown = useRef(filmId);

  const run = useCallback(
    async (text, yearText) => {
      const ticket = ++latest.current;
      controller.current?.abort();
      const mine = new AbortController();
      controller.current = mine;
      setSearch({ status: 'loading', results: [], problem: null });
      setPicked(null);
      setApplyProblem(null);
      try {
        const results = await adminApi.matchSearch(
          filmId,
          { query: text.trim(), year: parseYear(yearText) },
          { signal: mine.signal },
        );
        if (ticket === latest.current) setSearch({ status: 'ready', results, problem: null });
      } catch (failure) {
        if (ticket === latest.current) {
          setSearch({ status: 'error', results: [], problem: matchProblem(failure) });
        }
      }
    },
    [filmId],
  );

  // The film whose panel is on screen (null once it closed): an apply for any other film is stale when it ends.
  useEffect(() => {
    shown.current = filmId;
    return () => {
      shown.current = null;
    };
  }, [filmId]);

  // A replayed effect (StrictMode, a remount) cancels the search it started before, so only the last one counts.
  useEffect(() => {
    run(initialQuery, '');
    return () => {
      latest.current += 1;
      controller.current?.abort();
    };
  }, [run, initialQuery]);

  const submit = useCallback(() => run(query, year), [run, query, year]);

  const apply = useCallback(async () => {
    if (picked == null) return;
    setApplying(true);
    setApplyProblem(null);
    try {
      const applied = await adminApi.applyMatch(filmId, picked);
      announceLibraryChanged();
      onApplied(applied, shown.current !== filmId);
    } catch (failure) {
      setApplyProblem(matchProblem(failure));
    } finally {
      setApplying(false);
    }
  }, [filmId, picked, onApplied]);

  return {
    query,
    setQuery,
    year,
    setYear,
    search,
    submit,
    picked,
    setPicked,
    applying,
    applyProblem,
    apply,
  };
}
