import { useState } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import LibraryTabs, { COLLECTIONS_PATH } from '../components/LibraryTabs.jsx';
import { useCollections } from '../state/usePeopleAndCollections.js';

/**
 * The Library shell: heading, the tab-specific count line and the Films / Collections tabs.
 * Each tab (an `Outlet` route) reports its own count line through the outlet context.
 */
export default function Library() {
  const { pathname } = useLocation();
  const active = pathname.replace(/\/+$/, '') === COLLECTIONS_PATH ? 'collections' : 'films';
  const { data } = useCollections();
  const [countLabel, setCountLabel] = useState('');
  return (
    <div className="page__stack library">
      <div className="library__heading">
        <h1 className="page-title">Library</h1>
        <span className="library__count" role="status">
          {countLabel}
        </span>
        <LibraryTabs
          active={active}
          filmCount={data?.filmCount}
          collectionCount={data?.collections?.length}
        />
      </div>
      <div
        role="tabpanel"
        id="library-panel"
        aria-labelledby={`library-tab-${active}`}
        className="page__stack library__panel"
      >
        <Outlet context={{ setCountLabel }} />
      </div>
    </div>
  );
}
