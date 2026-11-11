import { useCallback, useEffect, useRef, useState } from 'react';
import { useHoverNone } from './useHoverNone.js';

/** How long a touch must be held on a card to open the sheet. */
export const LONG_PRESS_MS = 500;

/**
 * The behaviour shared by the cards that carry a ⋯ menu (Home's Continue watching cards and the Library's in-progress
 * posters): the popover or sheet state, a long press on touch that opens the sheet, and, while the card has focus,
 * Delete/Backspace to remove (only when `canRemove`) and M to mark watched. `wrapper` holds the props for the element
 * around the link and the ⋯ button; `button` those for the ⋯ button, which gets `moreRef`.
 */
export function useCardMenu({ film, canRemove = true, onRemove, onMarkWatched }) {
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
    if ((event.key === 'Delete' || event.key === 'Backspace') && canRemove) {
      event.preventDefault();
      onRemove(film);
    } else if (event.key === 'm' || event.key === 'M') {
      event.preventDefault();
      onMarkWatched(film);
    }
  };

  return {
    menu,
    moreRef,
    close,
    wrapper: {
      onKeyDown,
      onTouchStart: startPress,
      onTouchMove: cancelPress,
      onTouchEnd: cancelPress,
      onTouchCancel: cancelPress,
      onClickCapture: swallowClickAfterPress,
      onContextMenu: (event) => {
        if (press.current.fired) event.preventDefault();
      },
    },
    button: {
      ref: moreRef,
      'aria-label': `More options for ${film.title}`,
      'aria-haspopup': 'menu',
      'aria-expanded': menu !== null,
      onClick: () => (menu ? close(false) : setMenu(hoverNone ? 'sheet' : 'popover')),
    },
  };
}
