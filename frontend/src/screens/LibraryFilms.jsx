import { useEffect, useRef } from 'react';
import { useOutletContext, useSearchParams } from 'react-router-dom';
import CollectionTile from '../components/CollectionTile.jsx';
import { Picker, Segmented, Switch } from '../components/Controls.jsx';
import { SearchIcon } from '../components/icons.jsx';
import { Poster } from '../components/Poster.jsx';
import { GridSkeleton } from '../components/Skeleton.jsx';
import { EmptyLibrary, ErrorState, NoResults } from '../components/StateMessages.jsx';
import { groupFilms } from '../state/collections.js';
import { useCollections } from '../state/usePeopleAndCollections.js';
import { useGroupCollections } from '../state/useGroupCollections.js';
import { useUrlSearch } from '../state/useUrlSearch.js';
import { useFilms } from '../state/useFilms.js';

const SORTS = [
  { value: 'title', label: 'Title' },
  { value: 'year', label: 'Year' },
  { value: 'added', label: 'Recently added' },
  { value: 'imdb', label: 'IMDb rating' },
  { value: 'rt', label: 'Rotten Tomatoes' },
];
const SORT_PHRASE = {
  title: 'title',
  year: 'year',
  added: 'recently added',
  imdb: 'IMDb rating',
  rt: 'Rotten Tomatoes',
};
const SHOWS = [
  { value: 'all', label: 'All' },
  { value: 'unwatched', label: 'Unwatched' },
  { value: 'watched', label: 'Watched' },
];

export default function LibraryFilms() {
  const { setCountLabel } = useOutletContext();
  const [params, setParams] = useSearchParams();
  const sort = params.get('sort') || 'added';
  const genre = params.get('genre') || '';
  const decade = params.get('decade') || '';
  const show = params.get('show') || 'all';

  const { q, text, setText } = useUrlSearch(params, setParams);
  const [groupPreference, setGroupPreference] = useGroupCollections();
  const { data: collectionsData } = useCollections();

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

  const query = { q, sort, genre, decade, show: show === 'all' ? '' : show };
  const { data, error, loading, reload } = useFilms(query);

  const filtersActive = Boolean(q || genre || decade || show !== 'all');
  const facets = data?.facets || { genres: [], decades: [] };
  const total = data?.total ?? 0;
  // Search never groups: a search shows individual films even with the switch on.
  const grouping = groupPreference && !q;
  const items =
    data && grouping
      ? groupFilms(data.films, collectionsData?.collections || [], sort)
      : (data?.films || []).map((film) => ({ type: 'film', film }));
  const plural = (n, noun) => `${n} ${noun}${n === 1 ? '' : 's'}`;
  const sortedBy = `sorted by ${SORT_PHRASE[sort] || 'recently added'}`;
  let countLabel = '';
  if (data) {
    countLabel = grouping
      ? `${plural(total, 'film')} in ${plural(items.length, 'tile')} · ${sortedBy}`
      : `${plural(total, 'film')} · ${sortedBy}`;
  }

  useEffect(() => {
    setCountLabel(countLabel);
  }, [countLabel, setCountLabel]);

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
        {items.map((item) =>
          item.type === 'film' ? (
            <Poster key={item.film.id} film={item.film} />
          ) : (
            <CollectionTile
              key={`collection-${item.collection.id}`}
              collection={item.collection}
              films={item.films}
            />
          ),
        )}
      </div>
    );
  }

  return (
    <>
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
        <Segmented
          label="Show"
          options={SHOWS}
          value={show}
          onChange={(v) => update('show', v, 'all')}
        />
        <Switch label="Group collections" checked={groupPreference} onChange={setGroupPreference} />
      </div>

      {body}

      <div className="hints" aria-hidden="true">
        <span>/ search</span>
        <span>Tab move</span>
        <span>Enter open</span>
        <span>Esc clear</span>
      </div>
    </>
  );
}
