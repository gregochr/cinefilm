import { useRef } from 'react';
import { Link, useNavigate } from 'react-router-dom';

export const FILMS_PATH = '/library';
export const COLLECTIONS_PATH = '/library/collections';

const TABS = [
  { id: 'films', label: 'Films', to: FILMS_PATH },
  { id: 'collections', label: 'Collections', to: COLLECTIONS_PATH },
];

/**
 * The Library's Films / Collections tabs. Each tab is a link to its own URL; Left/Right and
 * Home/End move focus and navigate, and only the selected tab is in the tab order.
 */
export default function LibraryTabs({ active, filmCount, collectionCount }) {
  const navigate = useNavigate();
  const refs = useRef({});
  const counts = { films: filmCount, collections: collectionCount };

  const onKeyDown = (event) => {
    const at = TABS.findIndex((t) => t.id === active);
    let next = null;
    if (event.key === 'ArrowRight') next = (at + 1) % TABS.length;
    else if (event.key === 'ArrowLeft') next = (at + TABS.length - 1) % TABS.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = TABS.length - 1;
    if (next === null) return;
    event.preventDefault();
    refs.current[TABS[next].id]?.focus();
    navigate(TABS[next].to);
  };

  return (
    <div role="tablist" aria-label="Library" className="library__tabs">
      {TABS.map((tab) => {
        const selected = tab.id === active;
        const count = counts[tab.id];
        return (
          <Link
            key={tab.id}
            ref={(node) => {
              refs.current[tab.id] = node;
            }}
            to={tab.to}
            role="tab"
            id={`library-tab-${tab.id}`}
            aria-selected={selected}
            aria-controls="library-panel"
            tabIndex={selected ? 0 : -1}
            className="library__tab"
            onKeyDown={onKeyDown}
          >
            {tab.label}
            {count != null ? (
              <>
                {' '}
                <span className="library__tab-count">{count}</span>
              </>
            ) : null}
          </Link>
        );
      })}
    </div>
  );
}
