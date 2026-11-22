import AdminErrorBanner from '../../components/AdminErrorBanner.jsx';
import { ButtonLink } from '../../components/Button.jsx';
import { CREDITS_PATH } from '../../state/adminRoutes.js';
import { creditsCard } from '../../state/creditsAdminCopy.js';
import { useCreditsCounts } from '../../state/useCreditsAdmin.js';
import { useLibraryChanges } from './useLibraryChanges.js';
import { useSignInRecovery } from './useSignInRecovery.js';

/**
 * Library's "End credits" card: how many films need a look, have Plex's marker, have a detected one and have one the owner set, and the
 * way into the list. The counts come from the list's own first read.
 */
export default function EndCreditsCard() {
  // The counts are read again when a scan, the Plex import or the credits import finishes: each announces one library
  // change, so that is the one thing the key follows (`settledAt` would change first and send a second read).
  const { counts, loading, error, reload } = useCreditsCounts(
    useSignInRecovery(),
    useLibraryChanges(),
  );
  const tiles = [
    { key: 'none', label: creditsCard.needs },
    { key: 'plex', label: creditsCard.plex },
    { key: 'auto', label: creditsCard.auto },
    { key: 'owner', label: creditsCard.owner },
  ];
  return (
    <section id="end-credits" className="admin__card" aria-labelledby="admin-credits-title">
      <div className="admin__head">
        <h2 id="admin-credits-title" className="admin__title">
          {creditsCard.title}
        </h2>
      </div>
      {!counts && error && !loading ? (
        <AdminErrorBanner onRetry={reload} retryLabel={creditsCard.checkAgain}>
          {creditsCard.loadError}
        </AdminErrorBanner>
      ) : (
        <ul className="admin__counts" aria-label={creditsCard.tilesLabel}>
          {tiles.map((tile) => (
            <li key={tile.key} className="admin__count">
              <span className="admin__count-label">{tile.label}</span>
              <span className="admin__count-value">{counts ? counts[tile.key] : '–'}</span>
            </li>
          ))}
        </ul>
      )}
      <div className="admin__actions admin__actions--wrap">
        <ButtonLink to={CREDITS_PATH}>{creditsCard.review}</ButtonLink>
      </div>
    </section>
  );
}
