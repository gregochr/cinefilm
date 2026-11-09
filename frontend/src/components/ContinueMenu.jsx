import { useEffect, useId, useRef } from 'react';
import { createPortal } from 'react-dom';
import { Link } from 'react-router-dom';
import { formatLeft } from '../player/time.js';

const itemsOf = (menu) => [...menu.querySelectorAll('[role="menuitem"]')];

/**
 * The ⋯ menu of a Continue watching card: a popover under the button, or (`sheet`) a bottom sheet with a
 * header and a separate Cancel. `onClose(returnFocus)` closes it; Esc returns focus to the ⋯ button.
 */
export default function ContinueMenu({ film, sheet, anchorRef, onClose, onRemove, onMarkWatched }) {
  const panelRef = useRef(null);
  const menuRef = useRef(null);
  const labelId = useId();
  const subId = useId();
  const progress = film.progress;

  useEffect(() => {
    itemsOf(menuRef.current)[0]?.focus();
  }, []);

  useEffect(() => {
    const onOutside = (event) => {
      if (panelRef.current.contains(event.target) || anchorRef.current?.contains(event.target)) {
        return;
      }
      onClose(false);
    };
    document.addEventListener('mousedown', onOutside);
    document.addEventListener('touchstart', onOutside);
    return () => {
      document.removeEventListener('mousedown', onOutside);
      document.removeEventListener('touchstart', onOutside);
    };
  }, [anchorRef, onClose]);

  const onKeyDown = (event) => {
    const items = itemsOf(menuRef.current);
    const at = items.indexOf(document.activeElement);
    const go = (i) => {
      event.preventDefault();
      items[(i + items.length) % items.length].focus();
    };
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose(true);
    } else if (event.key === 'ArrowDown') go(at + 1);
    else if (event.key === 'ArrowUp') go(at < 0 ? -1 : at - 1);
    else if (event.key === 'Home') go(0);
    else if (event.key === 'End') go(-1);
    else if (event.key === 'Tab') onClose(false);
  };

  const body = (
    <div
      className={sheet ? 'sheet' : 'cmenu'}
      ref={panelRef}
      onKeyDown={onKeyDown}
      role={sheet ? 'dialog' : undefined}
      aria-modal={sheet ? 'true' : undefined}
      aria-label={sheet ? `Options for ${film.title}` : undefined}
    >
      <div className={sheet ? 'sheet__panel' : undefined}>
        {sheet ? (
          <div className="sheet__head">
            <div className="sheet__title">{film.title}</div>
            <div className="sheet__meta">
              {[film.year, progress ? formatLeft(progress.positionMs, progress.runtimeMs) : null]
                .filter(Boolean)
                .join(' · ')}
            </div>
          </div>
        ) : null}
        <div
          role="menu"
          aria-label={`Options for ${film.title}`}
          ref={menuRef}
          className="cmenu__list"
        >
          <button
            type="button"
            role="menuitem"
            className="cmenu__item"
            aria-labelledby={labelId}
            aria-describedby={subId}
            onClick={() => {
              onClose(false);
              onRemove();
            }}
          >
            <span id={labelId}>Remove from Continue watching</span>
            <span id={subId} className="cmenu__sub">
              Keeps your place if you come back to it
            </span>
          </button>
          <button
            type="button"
            role="menuitem"
            className="cmenu__item"
            onClick={() => {
              onClose(false);
              onMarkWatched();
            }}
          >
            Mark as watched
          </button>
          <Link
            role="menuitem"
            className="cmenu__item"
            to={`/films/${film.id}/play?edition=${progress?.editionId}&start=0`}
            onClick={() => onClose(false)}
          >
            Start from beginning
          </Link>
        </div>
      </div>
      {sheet ? (
        <button type="button" className="sheet__cancel" onClick={() => onClose(true)}>
          Cancel
        </button>
      ) : null}
    </div>
  );

  return sheet ? createPortal(body, document.body) : body;
}
