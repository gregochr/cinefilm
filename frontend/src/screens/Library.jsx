import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Picker, Segmented } from '../components/Controls.jsx';
import { SearchIcon } from '../components/icons.jsx';
import { Poster } from '../components/Poster.jsx';
import { GridSkeleton } from '../components/Skeleton.jsx';
import { EmptyLibrary, ErrorState, NoResults } from '../components/StateMessages.jsx';
import { useDebouncedValue } from '../state/useDebouncedValue.js';
import { useFilms } from '../state/useFilms.js';

const SORTS = [
  { value: 'title', label: 'Title' },
  { value: 'year', label: 'Year' },
  { value: 'added', label: 'Recently added' },
];
const SORT_PHRASE = { title: 'title', year: 'year', added: 'recently added' };
const SHOWS = [
  { value: 'all', label: 'All' },
  { value: 'unwatched', label: 'Unwatched' },
  { value: 'watched', label: 'Watched' },
];

export const SEARCH_DEBOUNCE_MS = 250;

export default function Library() {
  const [params, setParams] = useSearchParams();
  const q = params.get('q') || '';
  const sort = params.get('sort') || 'added';
  const genre = params.get('genre') || '';
  const decade = params.get('decade') || '';
  const collection = params.get('collection') || '';
  const show = params.get('show') || 'all';

  const [text, setText] = useState(q);
  const debounced = useDebouncedValue(text, SEARCH_DEBOUNCE_MS);
  const paramsRef = useRef(params);
  useEffect(() => {
    paramsRef.current = params;
  });
  useEffect(() => {
    const current = paramsRef.current;
    if (debounced === (current.get('q') || '')) return;
    const next = new URLSearchParams(current);
    if (debounced.trim()) next.set('q', debounced.trim());
    else next.delete('q');
    setParams(next, { replace: true });
  }, [debounced, setParams]);

  const update = (key, value, empty = '') => {
    const next = new URLSearchParams(params);
    if (value === empty || value === '') next.delete(key);
    else next.set(key, value);
    setParams(next, { replace: true });
  };

  const clearAll = () => {
    setText('');
    setParams(new URLSearchParams(), { replace: true });
  };

  const query = { q, sort, genre, decade, collection, show: show === 'all' ? '' : show };
  const { data, error, loading, reload } = useFilms(query);

  const filtersActive = Boolean(q || genre || decade || collection || show !== 'all');
  const facets = data?.facets || { genres: [], decades: [], collections: [] };
  const total = data?.total ?? 0;
  const countLabel = data
    ? `${total} ${total === 1 ? 'film' : 'films'} · sorted by ${SORT_PHRASE[sort] || 'recently added'}`
    : '';

  const autoFocus = params.has('focus');
  const searchRef = useRef(null);
  useEffect(() => {
    if (autoFocus) searchRef.current?.focus();
  }, [autoFocus]);

  let body;
  if (loading && !data) body = <GridSkeleton />;
  else if (error && !data) body = <ErrorState onRetry={reload} />;
  else if (data && total === 0) {
    body = filtersActive ? <NoResults query={q} onClear={clearAll} /> : <EmptyLibrary />;
  } else if (data) {
    body = (
      <div className={`poster-grid${loading ? ' poster-grid--busy' : ''}`}>
        {data.films.map((f) => (
          <Poster key={f.id} film={f} />
        ))}
      </div>
    );
  }

  return (
    <div className="page__stack library">
      <div className="library__heading">
        <h1 className="page-title">Library</h1>
        <span className="library__count" role="status">
          {countLabel}
        </span>
      </div>

      <div className="filters">
        <label className="field field--grow">
          <span className="field__label">Search</span>
          <span className="searchbox searchbox--lg">
            <SearchIcon size={16} />
            <input
              ref={searchRef}
              type="search"
              className="searchbox__input"
              placeholder={'Title, actor or director — try “de niro”'}
              value={text}
              onChange={(e) => setText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Escape' && text) {
                  e.preventDefault();
                  setText('');
                }
              }}
              data-global-search
            />
            <kbd className="searchbox__hint" aria-hidden="true">
              /
            </kbd>
          </span>
        </label>
        <Segmented
          label="Sort"
          options={SORTS}
          value={sort}
          onChange={(v) => update('sort', v, 'added')}
        />
        <Picker
          label="Genre"
          value={genre}
          onChange={(v) => update('genre', v)}
          options={[
            { value: '', label: 'All' },
            ...facets.genres.map((g) => ({ value: g, label: g })),
          ]}
        />
        <Picker
          label="Decade"
          value={decade}
          onChange={(v) => update('decade', v)}
          options={[
            { value: '', label: 'All' },
            ...facets.decades.map((d) => ({ value: String(d), label: `${d}s` })),
          ]}
        />
        <Picker
          label="Collection"
          value={collection}
          onChange={(v) => update('collection', v)}
          options={[
            { value: '', label: 'All' },
            ...facets.collections.map((c) => ({ value: String(c.id), label: c.name })),
          ]}
        />
        <Segmented
          label="Show"
          options={SHOWS}
          value={show}
          onChange={(v) => update('show', v, 'all')}
        />
      </div>

      {body}

      <div className="hints" aria-hidden="true">
        <span>/ search</span>
        <span>Tab move</span>
        <span>Enter open</span>
        <span>Esc clear</span>
      </div>
    </div>
  );
}
