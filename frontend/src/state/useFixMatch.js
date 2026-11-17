import { useCallback, useEffect, useRef, useState } from 'react';
import { announceLibraryChanged } from '../api/client.js';
import { adminApi } from '../api/resources.js';
import { matchProblem, parseYear } from './fixMatch.js';

const IDLE = {
  status: 'loading',
  results: [],
  problem: null,
  fileRuntimeMs: null,
  suggestedTmdbId: null,
  suggestedBecause: null,
};

/**
 * The Fix match panel for one film: searches TMDB (once on open with the film's title, then on demand), keeps the
 * picked result (the server's suggestion until the person picks another) and applies it. `onApplied` runs after a
 * successful apply.
 */
export function useFixMatch(filmId, initialQuery, onApplied) {
  const [query, setQuery] = useState(initialQuery);
  const [year, setYear] = useState('');
  const [search, setSearch] = useState(IDLE);
  const [picked, setPicked] = useState(null);
  const [applying, setApplying] = useState(false);
  const [applyProblem, setApplyProblem] = useState(null);
  const latest = useRef(0);
  const controller = useRef(null);
  const shown = useRef(filmId);
  // The file's length is about the film, not the search: the last answer's value stays through a new or failed search.
  const known = useRef({ filmId, ms: null });

  const run = useCallback(
    async (text, yearText) => {
      const ticket = ++latest.current;
      controller.current?.abort();
      const mine = new AbortController();
      controller.current = mine;
      const keep = known.current.filmId === filmId ? known.current.ms : null;
      setSearch({ ...IDLE, fileRuntimeMs: keep });
      setPicked(null);
      setApplyProblem(null);
      try {
        const answer = await adminApi.matchSearch(
          filmId,
          { query: text.trim(), year: parseYear(yearText) },
          { signal: mine.signal },
        );
        if (ticket === latest.current) {
          // Only the newest answer sets the default; a stale or aborted one never touches `picked`.
          setSearch({
            status: 'ready',
            results: answer.results,
            problem: null,
            fileRuntimeMs: answer.fileRuntimeMs ?? null,
            suggestedTmdbId: answer.suggestedTmdbId ?? null,
            suggestedBecause: answer.suggestedBecause ?? null,
          });
          known.current = { filmId, ms: answer.fileRuntimeMs ?? null };
          setPicked(answer.suggestedTmdbId ?? null);
        }
      } catch (failure) {
        if (ticket === latest.current) {
          setSearch({
            ...IDLE,
            status: 'error',
            fileRuntimeMs: keep,
            problem: matchProblem(failure),
          });
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
