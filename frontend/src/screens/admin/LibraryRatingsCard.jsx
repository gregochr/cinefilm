import { ActionWithHint } from '../../components/ActionWithHint.jsx';
import { buttons, libraryPage } from '../../state/adminCopy.js';
import { describeRatingsRefresh } from '../../state/ratings.js';
import { formatScanDay } from '../../state/scanFormat.js';

/**
 * The Ratings card: when IMDb and Rotten Tomatoes scores were last fetched, and "Refresh all ratings". It is not
 * drawn until the scan status has loaded (the status carries the ratings figures). Without an OMDb key the line says
 * so and the button is off.
 */
export default function LibraryRatingsCard({ scan, starting, notice, onRefresh }) {
  if (!scan) return null;
  const running = Boolean(scan.ratingsRunning);
  const enabled = Boolean(scan.ratingsEnabled);
  return (
    <section id="ratings" className="admin__card" aria-labelledby="admin-ratings-title">
      <div className="admin__head">
        <h2 id="admin-ratings-title" className="admin__title">
          {libraryPage.ratings.title}
        </h2>
        <span className="admin__mono admin__why">
          {enabled
            ? describeRatingsRefresh(scan.ratingsLastRefreshedAt, formatScanDay)
            : libraryPage.ratings.needsKey}
        </span>
      </div>
      <div className="admin__actions">
        <ActionWithHint
          layout="line"
          hint={buttons.refreshRatings.hint}
          disabled={!enabled || running || starting}
          onClick={onRefresh}
        >
          {running ? buttons.refreshRatings.busyLabel : buttons.refreshRatings.label}
        </ActionWithHint>
        {notice ? (
          <span className="admin__notice" role="status">
            {notice}
          </span>
        ) : null}
      </div>
    </section>
  );
}
