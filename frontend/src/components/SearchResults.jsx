import { memo } from 'react';
import { Link } from 'react-router-dom';
import { POSTER_SIZE_THUMB } from '../api/images.js';
import { formatLeft } from '../player/time.js';
import { filmCount, initials } from '../state/useSearchCombobox.js';
import Art from './Art.jsx';
import CollectionFan from './CollectionFan.jsx';

/** `text` with each `{ start, end }` range (offsets on `text`) in a `<mark class="search-hit">`. */
export function Highlighted({ text, ranges = [] }) {
  const parts = [];
  let at = 0;
  for (const { start, end } of [...ranges].sort((a, b) => a.start - b.start)) {
    const from = Math.max(start, at);
    const to = Math.min(end, text.length);
    if (to <= from) continue;
    if (from > at) parts.push(text.slice(at, from));
    parts.push(
      <mark key={from} className="search-hit" data-testid="search-hit">
        {text.slice(from, to)}
      </mark>,
    );
    at = to;
  }
  if (at < text.length) parts.push(text.slice(at));
  return <>{parts}</>;
}

function Lead({ row }) {
  const { item, groupKey } = row;
  if (groupKey === 'titles') {
    return (
      <Art
        kind="poster"
        path={item.posterPath}
        id={item.id}
        title={item.title}
        size={POSTER_SIZE_THUMB}
        showTitle={false}
        className="search-row__poster"
      />
    );
  }
  if (groupKey === 'collections') {
    return (
      <span className="search-row__fan">
        <CollectionFan posters={item.posterPaths.slice(0, 2)} seed={item.id} name={item.name} />
      </span>
    );
  }
  return (
    <span className="search-row__avatar" aria-hidden="true">
      {initials(item.name)}
    </span>
  );
}

function subtitle({ item, groupKey }) {
  if (groupKey === 'titles') return item.subtitle;
  if (groupKey === 'collections')
    return `Collection · ${item.ownedCount} of ${item.totalCount} films`;
  if (groupKey === 'actors') return `Actor · ${filmCount(item.actorCount)}`;
  return `Director · ${filmCount(item.directorCount)}`;
}

/** One option. Memoised: hovering changes `active` on two rows only (every other prop is stable). */
const Row = memo(function Row({ row, active, onHover, onOpen }) {
  const { item } = row;
  const name = item.title ?? item.name;
  return (
    // The option is operated from the combobox input (arrows, Enter); the click is for the mouse.
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events
    <div
      id={row.id}
      role="option"
      tabIndex={-1}
      aria-selected={active}
      className="search-row"
      data-active={active ? 'true' : undefined}
      onMouseMove={() => onHover(row.index)}
      onClick={() => onOpen(row)}
    >
      <Lead row={row} />
      <span className="search-row__text">
        <span className="search-row__name" data-testid="search-name">
          <Highlighted text={name} ranges={item.ranges} />
        </span>
        <span className="search-row__meta" data-testid="search-meta">
          {subtitle(row)}
        </span>
      </span>
      {row.groupKey === 'titles' && item.leftMs != null ? (
        <span className="search-row__left">{formatLeft(0, item.leftMs)}</span>
      ) : null}
    </div>
  );
});

const countLabel = (group) =>
  group.total > group.items.length ? `${group.items.length} of ${group.total}` : `${group.total}`;

/**
 * The results of a search, shared by the header dropdown and (later) the phone sheet. `size` is `md` (desktop rows)
 * or `lg` (52px phone rows). `rows` are the flat rows of `result` (see `flattenRows`); `activeIndex` is the one
 * selected; `onHover(index)` and `onOpen(row)` should be stable so memoised rows stay still.
 */
export default function SearchResults({
  status,
  result,
  rows,
  activeIndex,
  listboxId,
  size = 'md',
  onHover,
  onOpen,
  onSeeAll,
}) {
  const query = result.query.trim();
  const ready = status === 'ready';
  const capped = result.groups.some((group) => group.total > group.items.length);
  let at = 0;
  return (
    <div className={`search-results search-results--${size}`} data-testid="search-results">
      {status === 'loading' ? <p className="search-note">Loading…</p> : null}
      {status === 'error' ? (
        <p className="search-note">Search isn’t available right now. Enter searches the Library.</p>
      ) : null}
      {ready && result.fuzzy ? (
        <p className="search-note">No exact match for “{query}” · showing close matches</p>
      ) : null}
      {ready && result.total === 0 ? (
        <div className="search-empty">
          <p className="search-empty__title" data-testid="search-empty-title">
            No matches for “{query}”
          </p>
          <p className="search-note">Search looks at titles, collections, actors and directors.</p>
        </div>
      ) : null}
      <div role="listbox" id={listboxId} aria-label="Search results">
        {result.groups.map((group) => (
          <div key={group.key} role="group" aria-label={group.label} className="search-group">
            <div className="search-group__head" aria-hidden="true">
              <span className="search-group__label">{group.label}</span>
              <span className="search-group__count">{countLabel(group)}</span>
            </div>
            {group.items.map(() => {
              const row = rows[at++];
              return (
                <Row
                  key={row.id}
                  row={row}
                  active={row.index === activeIndex}
                  onHover={onHover}
                  onOpen={onOpen}
                />
              );
            })}
          </div>
        ))}
      </div>
      {ready && result.total > 0 ? (
        <div className="search-footer">
          {size === 'lg' ? null : (
            <span className="search-footer__hints">↑↓ move · Enter open · Esc close</span>
          )}
          {capped ? (
            <Link
              to={`/library?q=${encodeURIComponent(query)}`}
              className="search-footer__all"
              onClick={onSeeAll}
            >
              See all {result.total} results
            </Link>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
