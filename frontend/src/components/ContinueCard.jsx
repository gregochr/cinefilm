import { Link } from 'react-router-dom';
import { formatLeft } from '../player/time.js';
import Art from './Art.jsx';
import ContinueMenu from './ContinueMenu.jsx';
import { MoreIcon } from './icons.jsx';
import { LONG_PRESS_MS, useCardMenu } from './useCardMenu.js';

export { LONG_PRESS_MS };

/**
 * A 16:9 "continue watching" card with a progress fill and time left, a ⋯ menu (Remove, Mark as
 * watched, Start from beginning) and, while it has focus, Delete/Backspace to remove and M to mark watched.
 * The ⋯ button is a sibling of the link, never inside it. `entering` marks a card that has just joined the row
 * (it slides in, unless the user prefers reduced motion).
 */
export function ContinueCard({ film, onRemove, onMarkWatched, linkRef, entering = false }) {
  const progress = film.progress;
  const pct = progress?.runtimeMs
    ? Math.round((progress.positionMs / progress.runtimeMs) * 100)
    : 0;
  const left = progress ? formatLeft(progress.positionMs, progress.runtimeMs) : '';
  const { menu, moreRef, close, wrapper, button } = useCardMenu({ film, onRemove, onMarkWatched });

  return (
    // The wrapper only forwards keys and touches that start on the link or the ⋯ button.
    <div className="continue-card" {...wrapper}>
      <Link
        to={`/films/${film.id}`}
        className="continue-card__link"
        data-entering={entering ? 'true' : undefined}
        aria-label={`${film.title}, ${left}`}
        ref={linkRef}
      >
        <Art kind="still" path={film.backdropPath} id={film.id} title={film.title}>
          <span className="continue-card__track" aria-hidden="true">
            <span
              className="continue-card__fill"
              data-testid="continue-fill"
              style={{ width: `${pct}%` }}
            />
          </span>
        </Art>
        <span className="continue-card__meta">
          <span className="continue-card__title">{film.title}</span>
          <span className="continue-card__left">{left}</span>
        </span>
      </Link>
      <button type="button" className="continue-card__more" {...button}>
        <MoreIcon size={20} />
      </button>
      {menu ? (
        <ContinueMenu
          film={film}
          sheet={menu === 'sheet'}
          anchorRef={moreRef}
          onClose={close}
          onRemove={() => onRemove(film)}
          onMarkWatched={() => onMarkWatched(film)}
        />
      ) : null}
    </div>
  );
}
