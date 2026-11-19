import { useEffect, useMemo, useReducer, useState } from 'react';
import { getChristmasStates } from '../api/christmas.js';
import { resultState } from './christmas.js';
import { search } from './search.js';
import { INITIAL_COMBOBOX, comboboxReducer } from './useSearchCombobox.js';

/** How many film titles a search offers, and how long typing must pause before their states are asked for. */
export const RESULT_LIMIT = 8;
export const STATES_DEBOUNCE_MS = 200;

const TITLES_ONLY = { titles: RESULT_LIMIT, collections: 0, actors: 0, directors: 0 };

/**
 * The "Add a film" search: the query and the open panel (the header search's reducer), the matching film titles from
 * the client-side index, and where each stands for Christmas week.
 *
 * The states come from `/states` for the matched film ids once typing pauses. Each request is numbered by its effect;
 * when the matches change, or `refreshKey` does (Fix match saved), the older answer is dropped. An answer is only
 * ever a fallback: a film's state is read from `data` (the screen's lists) first (`resultState`), so a change made
 * here, or a poll, is shown at once and a later answer from before the change cannot put the old state back; each
 * answer carries the request number (`issued()`) it was asked at, so one asked after the data was is not
 * downgraded (see `resultState`). A failed read clears the answers: the rows then have no button.
 *
 * Returns `{ state, dispatch, setQuery, query, status, rows, total, ready, statesFailed }`. A row is
 * `{ id, filmId, tmdbId, title, year, posterPath, state }`; `state` is null until an answer arrives (no button).
 * `total` is the matches before the cap; `ready` is false while the index is loading or failed.
 */
export function useAddFilm({ index, status, data, version, issued, idPrefix, refreshKey }) {
  const [state, dispatch] = useReducer(comboboxReducer, INITIAL_COMBOBOX);
  const [answers, setAnswers] = useState(() => new Map());
  const [failedFor, setFailedFor] = useState(null);
  const titles = useMemo(() => {
    const found = search(index, state.query, { caps: TITLES_ONLY });
    return found.groups.find((group) => group.key === 'titles') ?? { items: [], total: 0 };
  }, [index, state.query]);

  const idsKey = titles.items.map((item) => item.id).join(',');

  useEffect(() => {
    if (idsKey === '') return undefined;
    let current = true;
    const controller = new AbortController();
    const timer = setTimeout(() => {
      const issuedAt = issued();
      getChristmasStates(idsKey.split(',').map(Number), { signal: controller.signal }).then(
        (answer) => {
          if (!current) return;
          setFailedFor(null);
          setAnswers((now) => {
            const next = new Map(now);
            for (const row of answer) next.set(row.filmId, { ...row, issuedAt });
            return next;
          });
        },
        () => {
          if (!current) return;
          // What was known before no longer fits what the screen says it could not check.
          setAnswers(new Map());
          setFailedFor(idsKey);
        },
      );
    }, STATES_DEBOUNCE_MS);
    return () => {
      current = false;
      clearTimeout(timer);
      controller.abort();
    };
  }, [idsKey, refreshKey, issued]);

  const rows = titles.items.map((item) => {
    const answer = answers.get(item.id);
    // A film already in a list has its TMDB id there even before (or without) an answer.
    const listed = [...data.inWeek, ...data.leftOut].find((film) => film.filmId === item.id);
    return {
      id: `${idPrefix}-${item.id}`,
      filmId: item.id,
      tmdbId: answer?.tmdbId ?? listed?.tmdbId ?? null,
      title: item.title,
      year: item.year,
      posterPath: item.posterPath,
      state: resultState(item.id, data, answer, version),
    };
  });

  return {
    state,
    dispatch,
    setQuery: (query) => dispatch({ type: 'type', query }),
    query: state.query,
    status,
    rows,
    total: titles.total,
    ready: status === 'ready',
    statesFailed: failedFor !== null && failedFor === idsKey,
  };
}
