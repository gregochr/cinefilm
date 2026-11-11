import { useState } from 'react';
import { filmsApi } from '../api/resources.js';
import { useDebouncedValue } from './useDebouncedValue.js';
import { useResource } from './useResource.js';

/** A search needs this many characters before the library is asked. */
export const MIN_QUERY = 2;

/** At most this many films are offered. */
export const MAX_RESULTS = 8;

const NONE = { films: [] };

/**
 * The "Attach to…" picker: search the library for a film, pick one of its editions and, for a split edition, the
 * whole film or one part. Searches `GET /api/films?q=`; the film's editions come from `GET /api/films/{id}`.
 * A film with a single edition has it picked already. `target` is the body of the attach request once there is one.
 */
export function useAttachPicker() {
  const [query, setQuery] = useState('');
  const [filmId, setFilmId] = useState(null);
  const [editionId, setEditionId] = useState(null);
  const [part, setPart] = useState(null);
  const text = useDebouncedValue(query.trim());
  const searching = text.length >= MIN_QUERY;

  const found = useResource(`attach-films:${text}`, (options) =>
    searching ? filmsApi.list({ q: text }, options) : Promise.resolve(NONE),
  );
  const film = useResource(`attach-film:${filmId}`, (options) =>
    filmId == null ? Promise.resolve(null) : filmsApi.get(filmId, options),
  );

  // The previous film's answer stays in `film.data` while the next one loads: only this film's own counts.
  const editions = film.data?.id === filmId ? film.data.editions : [];
  const edition =
    editions.find((e) => e.id === editionId) ?? (editions.length === 1 ? editions[0] : null);

  return {
    query,
    setQuery: (value) => {
      setQuery(value);
      setFilmId(null);
      setEditionId(null);
      setPart(null);
    },
    searching,
    results: (found.data?.films ?? []).slice(0, MAX_RESULTS),
    searchLoading: searching && found.loading,
    searchError: searching && found.error != null,
    retrySearch: found.reload,
    filmId,
    pickFilm: (id) => {
      setFilmId(id);
      setEditionId(null);
      setPart(null);
    },
    editions,
    editionsLoading: filmId != null && film.data?.id !== filmId && film.error == null,
    editionsError: filmId != null && film.error != null,
    retryEditions: film.reload,
    edition,
    pickEdition: (id) => {
      setEditionId(id);
      setPart(null);
    },
    part,
    pickPart: setPart,
    target: edition ? { editionId: edition.id, part: edition.parts > 1 ? part : null } : null,
  };
}
