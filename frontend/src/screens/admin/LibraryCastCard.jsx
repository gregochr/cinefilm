import PlexCreditsImport from '../../components/PlexCreditsImport.jsx';
import { SkeletonBlock } from '../../components/Skeleton.jsx';
import { libraryPage } from '../../state/adminCopy.js';
import { describeCreditsCount } from '../../state/scanFormat.js';

/**
 * The Cast & crew card: how many films have credits (from the scan status), and, only when Plex is configured, the
 * "Import credits from Plex" block. The count means something without Plex, so the card is always drawn. There is no
 * TMDB credits download here: the server's credits dataset job trains the end-credits detector, not cast and crew.
 */
export default function LibraryCastCard({ scan, loading, plex }) {
  const configured = Boolean(plex?.configured);
  // Nothing to show when the status could not be read and there is no Plex block either.
  if (!scan && !loading && !configured) return null;
  return (
    <section id="cast-crew" className="admin__card" aria-labelledby="admin-cast-title">
      <div className="admin__head">
        <h2 id="admin-cast-title" className="admin__title">
          {libraryPage.cast.title}
        </h2>
        {scan ? (
          <span className="admin__mono admin__why">{describeCreditsCount(scan.counts)}</span>
        ) : null}
      </div>
      {!scan && loading ? (
        <div role="status" aria-label="Loading">
          <SkeletonBlock style={{ height: 24 }} />
        </div>
      ) : null}
      {configured ? <PlexCreditsImport reachable={plex.reachable} /> : null}
    </section>
  );
}
