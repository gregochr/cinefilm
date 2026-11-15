import { SkeletonBlock } from '../../components/Skeleton.jsx';
import { libraryPage } from '../../state/adminCopy.js';
import { describeCreditsCount } from '../../state/scanFormat.js';

/**
 * The Cast & crew card: how many films have credits, from the scan status. It holds a skeleton while the status
 * loads and is not drawn when the status failed (the scan card says so). There is no import here: "Import credits
 * from Plex" copies end-credits start markers, not cast and crew, so it sits with the Plex import; and there is no
 * TMDB credits download, because the server's credits dataset job trains the end-credits detector.
 */
export default function LibraryCastCard({ scan, loading }) {
  if (!scan && !loading) return null;
  return (
    <section id="cast-crew" className="admin__card" aria-labelledby="admin-cast-title">
      <div className="admin__head">
        <h2 id="admin-cast-title" className="admin__title">
          {libraryPage.cast.title}
        </h2>
        {scan ? <span className="admin__why">{describeCreditsCount(scan.counts)}</span> : null}
      </div>
      {!scan ? (
        <div role="status" aria-label="Loading">
          <SkeletonBlock style={{ height: 24 }} />
        </div>
      ) : null}
    </section>
  );
}
