import { useEffect, useRef, useState } from 'react';
import { useOutletContext, useSearchParams } from 'react-router-dom';
import CollectionTile from '../components/CollectionTile.jsx';
import { Picker, Segmented, Switch } from '../components/Controls.jsx';
import { SearchIcon } from '../components/icons.jsx';
import { PosterWithMenu } from '../components/PosterWithMenu.jsx';
import { GridSkeleton } from '../components/Skeleton.jsx';
import { EmptyLibrary, ErrorState, NoResults } from '../components/StateMessages.jsx';
import UndoToast from '../components/UndoToast.jsx';
import { groupFilms } from '../state/collections.js';
import { useLibraryFilmActions } from '../state/useLibraryFilmActions.js';
import { useCollections } from '../state/usePeopleAndCollections.js';
import { useGroupCollections } from '../state/useGroupCollections.js';
import { useUrlSearch } from '../state/useUrlSearch.js';
import { useFilms } from '../state/useFilms.js';

const SORTS = [
  { value: 'title', label: 'Title' },
  { value: 'year', label: 'Year' },
  { value: 'added', label: 'Recently added' },
  { value: 'last-watched', label: 'Last watched' },
  { value: 'imdb', label: 'IMDb rating' },
  { value: 'rt', label: 'Rotten Tomatoes' },
];
const SORT_PHRASE = {
  title: 'title',
  year: 'year',
  added: 'recently added',
  'last-watched': 'last watched',
  imdb: 'IMDb rating',
  rt: 'Rotten Tomatoes',
};
const SHOWS = [
  { value: 'all', label: 'All' },
  { value: 'in-progress', label: 'In progress' },
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

  // In progress is a list of what was last watched, so choosing it also sorts that way (in one update).
  const changeShow = (value) => {
    const next = new URLSearchParams(params);
    if (value === 'all') next.delete('show');
    else next.set('show', value);
    if (value === 'in-progress') next.set('sort', 'last-watched');
    setParams(next, { replace: true });
  };

  const clearAll = () => {
    setText('');
    setParams(new URLSearchParams(), { replace: true });
  };

  const query = { q, sort, genre, decade, show: show === 'all' ? '' : show };
  const { data, error, loading, reload } = useFilms(query);

  const undoButton = useRef(null);
  const focusUndo = useRef(false);
  const pendingFocus = useRef(null);
  const posterLinks = useRef(new Map());
  const searchRef = useRef(null);
  const prevAction = useRef(null);
  const undoLost = useRef(false); // the toast went while focus was on its Undo button
  const expiryTarget = useRef(null); // the poster focus goes to then: the next in the list, else the previous
  const [acted, setActed] = useState(0); // counts actions; an effect from before the latest one must not focus
  const actedWanted = useRef(0);
  const latestData = useRef(data);
  const awaitData = useRef(false); // the poster is rebuilt with the data being read: focus it only then
  const focusedAt = useRef(null); // the loaded data when pendingFocus was set
  const cw = useLibraryFilmActions(
    data,
    show,
    reload,
    (film) => {
      // A rollback brings the poster back: focus that was on Undo (or lost with the poster) follows it.
      const at = document.activeElement;
      if (at === document.body || at === undoButton.current) {
        pendingFocus.current = film.id;
        focusedAt.current = data;
      }
    },
    loading,
  );
  const { films, total } = cw;
  // When the toast goes (it expires, or the action is undone or rolled back) its button leaves with the focus on it.
  // The button is still in the DOM while this renders.
  if (prevAction.current && !cw.action)
    undoLost.current = document.activeElement === undoButton.current;
  prevAction.current = cw.action;
  const undo = () => {
    if (!cw.action) return;
    const id = cw.action.film.id;
    pendingFocus.current = id; // focus follows the poster that comes back
    focusedAt.current = data;
    cw.undo().then((reloading) => {
      // The list is being read again and the poster is rebuilt with it: focus it once that data arrives.
      if (!reloading) return;
      pendingFocus.current = id;
      focusedAt.current = latestData.current;
      awaitData.current = true;
    });
  };
  const filtersActive = Boolean(q || genre || decade || show !== 'all');
  const facets = data?.facets || { genres: [], decades: [] };

  useEffect(() => {
    latestData.current = data;
  });
  // A poster that leaves the list takes focus with it: focus moves to Undo, or back to the poster if it returns.
  useEffect(() => {
    if (focusUndo.current) {
      focusUndo.current = false;
      undoButton.current?.focus();
    } else if (pendingFocus.current !== null) {
      if (actedWanted.current > acted) return; // a render from before the action: its posters are stale
      const link = posterLinks.current.get(pendingFocus.current);
      const arrived = data !== focusedAt.current;
      if (awaitData.current && !arrived) return;
      if (link) {
        awaitData.current = false;
        link.focus();
        pendingFocus.current = null;
      } else if (arrived) {
        awaitData.current = false;
        // The poster may still be on its way (Undo after a reload waits for the list); once newer data arrives
        // without it, it is not coming, and a late focus must not be stolen.
        pendingFocus.current = null;
      }
    }
  });
  useEffect(() => {
    if (!undoLost.current) return;
    undoLost.current = false;
    // Undo and rollbacks move focus to the poster that came back (done, or still pending): leave it.
    if (pendingFocus.current !== null || document.activeElement !== document.body) return;
    (posterLinks.current.get(expiryTarget.current) ?? searchRef.current)?.focus();
  });
  const registerPoster = (id) => (el) => {
    if (el) posterLinks.current.set(id, el);
    else posterLinks.current.delete(id);
  };
  const act = (kind, film) => {
    focusUndo.current = kind === 'watched' && show === 'in-progress';
    const at = films.findIndex((f) => f.id === film.id);
    expiryTarget.current = focusUndo.current ? (films[at + 1] ?? films[at - 1])?.id : film.id;
    awaitData.current = false;
    pendingFocus.current = null; // a new action cancels a focus still waiting for a poster
    // A poster that stays (Remove, or Mark as watched in All) may be rebuilt and loses the focus its menu item had.
    if (!focusUndo.current) {
      pendingFocus.current = film.id;
      focusedAt.current = data;
      actedWanted.current = acted + 1;
      setActed(acted + 1);
    }
    (kind === 'remove' ? cw.remove : cw.markWatched)(film);
  };
  // Search and In progress never group: they show individual films even with the switch on.
  const grouping = groupPreference && !q && show !== 'in-progress';
  const items =
    data && grouping
      ? groupFilms(films, collectionsData?.collections || [], sort)
      : films.map((film) => ({ type: 'film', film }));
  const plural = (n, noun) => `${n} ${noun}${n === 1 ? '' : 's'}`;
  const sortedBy = `sorted by ${SORT_PHRASE[sort] || 'recently added'}`;
  let countLabel = '';
  if (data) {
    countLabel = grouping
      ? `${plural(total, 'film')} in ${plural(items.length, 'tile')} · ${sortedBy}`
      : `${plural(total, 'film')}${show === 'in-progress' ? ' in progress' : ''} · ${sortedBy}`;
  }

  useEffect(() => {
    setCountLabel(countLabel);
  }, [countLabel, setCountLabel]);

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
            <PosterWithMenu
              key={item.film.id}
              film={item.film}
              linkRef={registerPoster(item.film.id)}
              onRemove={(film) => act('remove', film)}
              onMarkWatched={(film) => act('watched', film)}
            />
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
              ref={searchRef}
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
        <Segmented label="Show" options={SHOWS} value={show} onChange={changeShow} />
        <Switch label="Group collections" checked={groupPreference} onChange={setGroupPreference} />
      </div>

      {body}
      <UndoToast action={cw.action} undoRef={undoButton} onUndo={undo} />

      <div className="hints" aria-hidden="true">
        <span>/ search</span>
        <span>Tab move</span>
        <span>Enter open</span>
        <span>Esc clear</span>
      </div>
    </>
  );
}
