import { useEffect, useMemo, useReducer, useState } from 'react';
import { SIGNED_IN_EVENT } from '../api/client.js';
import { getChristmasStates } from '../api/christmas.js';
import { resultState } from './christmas.js';
import { search } from './search.js';
import { INITIAL_COMBOBOX, comboboxReducer } from './useSearchCombobox.js';

/** How many film titles a search offers, and how long typing must pause before their states are asked for. */
export const RESULT_LIMIT = 8;
export const STATES_DEBOUNCE_MS = 200;

const CAPS = { titles: RESULT_LIMIT };

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
 * A film that was in a list when shown and has left every list since (a write made here) is remembered with the state
 * it had, so before any answer it reads as `NOT_IN` (`resultState` does the comparison) and its button changes in place
 * instead of disappearing. A sign-in after a 401 asks again for the ids on screen.
 *
 * Returns `{ state, dispatch, setQuery, query, status, rows, total, ready, statesFailed }`. A row is
 * `{ id, filmId, tmdbId, title, year, posterPath, state }`; `state` is null until an answer arrives (no button).
 * `total` is the matches before the cap; `ready` is false while the index is loading or failed.
 */
export function useAddFilm({ index, status, data, version, issued, idPrefix, refreshKey }) {
  const [state, dispatch] = useReducer(comboboxReducer, INITIAL_COMBOBOX);
  const [answers, setAnswers] = useState(() => new Map());
  const [failedFor, setFailedFor] = useState(null);
  const [recovered, setRecovered] = useState(0);
  const [listedBefore, setListedBefore] = useState(() => new Map());
  // Only the films are searched: a person's or collection's exact match must not stop the fuzzy title fallback.
  const filmsOnly = useMemo(
    () => (index ? { films: index.films, collections: [], people: [] } : index),
    [index],
  );
  const titles = useMemo(() => {
    const found = search(filmsOnly, state.query, { caps: CAPS });
    return found.groups.find((group) => group.key === 'titles') ?? { items: [], total: 0 };
  }, [filmsOnly, state.query]);

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
  }, [idsKey, refreshKey, issued, recovered]);

  // A 401 shows the sign-in card over this screen, which stays mounted: ask again once signed in.
  useEffect(() => {
    const onSignedIn = () => setRecovered((n) => n + 1);
    window.addEventListener(SIGNED_IN_EVENT, onSignedIn);
    return () => window.removeEventListener(SIGNED_IN_EVENT, onSignedIn);
  }, []);

  // Remember each shown film that the lists hold, with the data's stamp, for the case of it leaving them.
  useEffect(() => {
    const seen = [];
    for (const item of titles.items) {
      const held = [...data.inWeek, ...data.leftOut].find((film) => film.filmId === item.id);
      if (held) {
        seen.push([
          item.id,
          {
            state: resultState(item.id, data, undefined, version),
            tmdbId: held.tmdbId,
            issuedAt: version,
          },
        ]);
      }
    }
    if (seen.length === 0) return;
    setListedBefore((now) => {
      const next = new Map(now);
      for (const [id, entry] of seen) next.set(id, entry);
      return next;
    });
  }, [titles, data, version]);

  const rows = titles.items.map((item) => {
    const answer = answers.get(item.id) ?? listedBefore.get(item.id);
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
