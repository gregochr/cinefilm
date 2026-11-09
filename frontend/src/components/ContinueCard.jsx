import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { formatLeft } from '../player/time.js';
import Art from './Art.jsx';
import ContinueMenu from './ContinueMenu.jsx';
import { MoreIcon } from './icons.jsx';
import { useHoverNone } from './useHoverNone.js';

/** How long a touch must be held on a card to open the sheet. */
export const LONG_PRESS_MS = 500;

/**
 * A 16:9 "continue watching" card with a progress fill and time left, a ⋯ menu (Remove, Mark as
 * watched, Start from beginning) and, while it has focus, Delete/Backspace to remove and M to mark watched.
 * The ⋯ button is a sibling of the link, never inside it.
 */
export function ContinueCard({ film, onRemove, onMarkWatched, linkRef }) {
  const progress = film.progress;
  const pct = progress?.runtimeMs
    ? Math.round((progress.positionMs / progress.runtimeMs) * 100)
    : 0;
  const left = progress ? formatLeft(progress.positionMs, progress.runtimeMs) : '';
  const hoverNone = useHoverNone();
  const [menu, setMenu] = useState(null); // null | 'popover' | 'sheet'
  const moreRef = useRef(null);
  const press = useRef({ timer: null, fired: false });

  const close = useCallback((returnFocus) => {
    setMenu(null);
    if (returnFocus) moreRef.current?.focus();
  }, []);

  useEffect(() => () => clearTimeout(press.current.timer), []);

  const cancelPress = () => clearTimeout(press.current.timer);
  // The sheet is portalled out of the card but its events still bubble through React: ignore them.
  const outside = (event) => !event.currentTarget.contains(event.target);
  const startPress = (event) => {
    if (outside(event)) return;
    press.current.fired = false;
    cancelPress();
    if (event.touches.length !== 1) return;
    press.current.timer = setTimeout(() => {
      press.current.fired = true;
      setMenu('sheet');
    }, LONG_PRESS_MS);
  };
  const swallowClickAfterPress = (event) => {
    if (!press.current.fired || outside(event)) return;
    press.current.fired = false;
    event.preventDefault();
    event.stopPropagation();
  };

  const onKeyDown = (event) => {
    if (menu || event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) return;
    if (event.key === 'Delete' || event.key === 'Backspace') {
      event.preventDefault();
      onRemove(film);
    } else if (event.key === 'm' || event.key === 'M') {
      event.preventDefault();
      onMarkWatched(film);
    }
  };

  return (
    // The wrapper only forwards keys and touches that start on the link or the ⋯ button.
    // eslint-disable-next-line jsx-a11y/no-static-element-interactions
    <div
      className="continue-card"
      onKeyDown={onKeyDown}
      onTouchStart={startPress}
      onTouchMove={cancelPress}
      onTouchEnd={cancelPress}
      onTouchCancel={cancelPress}
      onClickCapture={swallowClickAfterPress}
      onContextMenu={(event) => {
        if (press.current.fired) event.preventDefault();
      }}
    >
      <Link
        to={`/films/${film.id}`}
        className="continue-card__link"
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
      <button
        type="button"
        className="continue-card__more"
        ref={moreRef}
        aria-label={`More options for ${film.title}`}
        aria-haspopup="menu"
        aria-expanded={menu !== null}
        onClick={() => (menu ? close(false) : setMenu(hoverNone ? 'sheet' : 'popover'))}
      >
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
