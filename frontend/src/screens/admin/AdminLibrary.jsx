import { useEffect, useRef } from 'react';
import { useLocation } from 'react-router-dom';
import { useAdminContext } from '../../state/adminContext.js';
import { useGroupingReport } from '../../state/useAdmin.js';
import LibraryCastCard from './LibraryCastCard.jsx';
import LibraryPlexCard from './LibraryPlexCard.jsx';
import LibraryRatingsCard from './LibraryRatingsCard.jsx';
import LibraryScanCard from './LibraryScanCard.jsx';
import SectionTitle from './SectionTitle.jsx';

/**
 * Library: the scan (with what it changed), the ratings, the Plex import (with the credits-marker import) and
 * Cast & crew. Opens on `#scan` or
 * `#plex-import` when asked.
 */
export default function AdminLibrary() {
  const {
    scan,
    error,
    loading,
    starting,
    notice,
    startScan,
    ratingsStarting,
    ratingsNotice,
    refreshRatings,
    reload,
    plexImport,
    settledAt,
    changed,
  } = useAdminContext();
  const grouping = useGroupingReport(`${settledAt}|${changed}`);
  const { hash } = useLocation();

  // Scrolls to a card named by the address once the card is there (a background refresh of the status does not
  // move the page): the scan card for `#scan`, the Plex card for `#plex-import`.
  const scanShown = scan != null;
  // The card is also drawn (as an error with Try again) when the very first read of the Plex status failed.
  const plexShown =
    Boolean(plexImport.plex?.configured) || (!plexImport.plex && Boolean(plexImport.error));
  useEffect(() => {
    if (hash === '#scan' && scanShown) document.getElementById('scan')?.scrollIntoView?.();
  }, [hash, scanShown]);
  // Once only: a failed "Try again" briefly un-shows the error card, and must not scroll the page again.
  const plexScrolled = useRef(false);
  useEffect(() => {
    if (hash === '#plex-import' && plexShown && !plexScrolled.current) {
      plexScrolled.current = true;
      document.getElementById('plex-import')?.scrollIntoView?.();
    }
  }, [hash, plexShown]);

  return (
    <>
      <SectionTitle>Library</SectionTitle>
      <div className="admin__grid">
        <LibraryScanCard
          scan={scan}
          loading={loading}
          error={error}
          starting={starting}
          notice={notice}
          onScan={startScan}
          onRetry={reload}
          changes={grouping}
        />
        <LibraryRatingsCard
          scan={scan}
          starting={ratingsStarting}
          notice={ratingsNotice}
          onRefresh={refreshRatings}
        />
        <LibraryPlexCard
          plex={plexImport.plex}
          error={plexImport.error}
          loading={plexImport.loading}
          starting={plexImport.starting}
          notice={plexImport.notice}
          onImport={plexImport.startImport}
          onRecheck={plexImport.reload}
        />
        <LibraryCastCard scan={scan} loading={loading} />
      </div>
    </>
  );
}
