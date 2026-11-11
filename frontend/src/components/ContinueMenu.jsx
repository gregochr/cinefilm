import { useEffect, useId, useLayoutEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { Link } from 'react-router-dom';
import { formatLeft } from '../player/time.js';

const itemsOf = (menu) => [...menu.querySelectorAll('[role="menuitem"]')];

/**
 * The ⋯ menu of a Continue watching card: a popover under the button, or (`sheet`) a bottom sheet with a
 * header and a separate Cancel. `onClose(returnFocus)` closes it; Esc returns focus to the ⋯ button.
 * `canRemove` false leaves out Remove from Continue watching (the film is already out of it).
 */
export default function ContinueMenu({
  film,
  sheet,
  anchorRef,
  onClose,
  onRemove,
  onMarkWatched,
  canRemove = true,
}) {
  const panelRef = useRef(null);
  const menuRef = useRef(null);
  const cancelRef = useRef(null);
  const labelId = useId();
  const subId = useId();
  const progress = film.progress;

  useEffect(() => {
    itemsOf(menuRef.current)[0]?.focus({ preventScroll: true });
  }, []);

  // The popover is portalled out of the (possibly scrolling) row: place it under the ⋯ button, right-aligned
  // to it, flipped above when it would run off the bottom and clamped inside the viewport.
  useLayoutEffect(() => {
    if (sheet) return;
    const panel = menuRef.current;
    const rect = anchorRef.current.getBoundingClientRect();
    const width = panel.offsetWidth;
    const height = panel.offsetHeight;
    const margin = 8;
    const left = Math.max(margin, Math.min(rect.right - width, window.innerWidth - width - margin));
    let top = rect.bottom + margin;
    if (top + height > window.innerHeight - margin) top = rect.top - height - margin;
    panel.style.left = `${left}px`;
    panel.style.top = `${Math.max(margin, top)}px`;
  }, [sheet, anchorRef]);

  // A popover that is not tied to its anchor any more (resize, any scroll) just closes.
  useEffect(() => {
    if (sheet) return undefined;
    const dismiss = () => onClose(false);
    window.addEventListener('resize', dismiss);
    window.addEventListener('scroll', dismiss, true);
    return () => {
      window.removeEventListener('resize', dismiss);
      window.removeEventListener('scroll', dismiss, true);
    };
  }, [sheet, onClose]);

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
    else if (event.key === 'ArrowUp') go(at - 1);
    else if (event.key === 'Home') go(0);
    else if (event.key === 'End') go(-1);
    else if (event.key === 'Tab') {
      if (!sheet) {
        onClose(false);
        return;
      }
      // The sheet is modal: Tab cycles through the items and Cancel, never out of the dialog.
      event.preventDefault();
      const ring = [...items, cancelRef.current];
      const from = ring.indexOf(document.activeElement);
      ring[(from + (event.shiftKey ? -1 : 1) + ring.length) % ring.length].focus();
    }
  };

  const body = (
    <div
      className={sheet ? 'sheet' : undefined}
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
          className={sheet ? 'cmenu__list' : 'cmenu cmenu__list'}
        >
          {canRemove ? (
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
          ) : null}
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
        <button
          type="button"
          className="sheet__cancel"
          ref={cancelRef}
          onClick={() => onClose(true)}
        >
          Cancel
        </button>
      ) : null}
    </div>
  );

  return createPortal(body, document.body);
}
