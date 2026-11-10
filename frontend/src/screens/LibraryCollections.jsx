import { useEffect } from 'react';
import { useOutletContext, useSearchParams } from 'react-router-dom';
import CollectionCard from '../components/CollectionCard.jsx';
import { Segmented } from '../components/Controls.jsx';
import { SearchIcon } from '../components/icons.jsx';
import { GridSkeleton } from '../components/Skeleton.jsx';
import { ErrorState, NoResults } from '../components/StateMessages.jsx';
import { browseCollections } from '../state/collections.js';
import { useCollections } from '../state/usePeopleAndCollections.js';
import { useUrlSearch } from '../state/useUrlSearch.js';

const SORTS = [
  { value: 'name', label: 'Name' },
  { value: 'added', label: 'Recently added to' },
  { value: 'size', label: 'Size' },
];
const SHOWS = [
  { value: 'all', label: 'All' },
  { value: 'complete', label: 'Complete' },
  { value: 'missing', label: 'Missing films' },
];

export default function LibraryCollections() {
  const { setCountLabel } = useOutletContext();
  const [params, setParams] = useSearchParams();
  const sort = params.get('sort') || 'name';
  const show = params.get('show') || 'all';
  const { q, text, setText } = useUrlSearch(params, setParams);
  const { data, error, loading, reload } = useCollections();

  const update = (key, value, empty) => {
    const next = new URLSearchParams(params);
    if (value === empty) next.delete(key);
    else next.set(key, value);
    setParams(next, { replace: true });
  };
  const clearAll = () => {
    setText('');
    setParams(new URLSearchParams(), { replace: true });
  };

  const shown = data ? browseCollections(data.collections, { q, sort, show }) : null;
  const total = shown?.length;
  useEffect(() => {
    setCountLabel(total == null ? '' : `${total} ${total === 1 ? 'collection' : 'collections'}`);
  }, [total, setCountLabel]);

  let body = null;
  if (loading && !data) body = <GridSkeleton />;
  else if (error && !data) body = <ErrorState onRetry={reload} />;
  else if (shown && shown.length === 0) {
    body =
      q || show !== 'all' ? (
        <NoResults
          query={q}
          onClear={clearAll}
          noun="collections"
          hint="Search looks at collection names."
        />
      ) : (
        <div className="state">
          <p className="state__text">
            No collections yet. A collection needs two of its films in the library.
          </p>
        </div>
      );
  } else if (shown) {
    body = (
      <div className="collection-grid">
        {shown.map((c) => (
          <CollectionCard key={c.id} collection={c} />
        ))}
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
              placeholder="Collection name"
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
          onChange={(v) => update('sort', v, 'name')}
        />
        <Segmented
          label="Show"
          options={SHOWS}
          value={show}
          onChange={(v) => update('show', v, 'all')}
        />
      </div>
      {body}
    </>
  );
}
