import { useCallback, useEffect, useId, useLayoutEffect, useRef } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useSearchIndex } from '../state/searchIndex.js';
import { openedFromApp, withoutSheet } from '../state/searchSheet.js';
import {
  announcement,
  hasQuery,
  interpretKey,
  useSearchCombobox,
} from '../state/useSearchCombobox.js';
import { SearchIcon } from './icons.jsx';
import { releaseKeyboardProxy } from './searchKeyboard.js';
import SearchResults from './SearchResults.jsx';

const LOCK_CLASS = 'search-sheet-open';

/**
 * The phone search: a full-screen sheet over a `?search=1` route (opened by the TabBar's Search tab). The same combobox
 * and results as the header dropdown, in 52px rows. Cancel goes back when the tab pushed this entry, otherwise it drops
 * the param; opening a result replaces the `search=1` entry, so Back from the film returns to where you were.
 */
export default function SearchSheet() {
  const navigate = useNavigate();
  const location = useLocation();
  const { index, status } = useSearchIndex();
  const { state, result, rows, dispatch, setQuery } = useSearchCombobox(index);
  const inputRef = useRef(null);
  const listboxId = useId();
  const open = hasQuery(state.query);
  const activeRow = open && status === 'ready' ? (rows[state.active] ?? null) : null;
  const message = announcement({ open, status, result });

  // Focus before paint (the Search tab already raised the keyboard); hand the keyboard over from its stand-in input.
  useLayoutEffect(() => {
    inputRef.current?.focus();
    releaseKeyboardProxy();
  }, []);

  // Nothing behind scrolls while the sheet is up; focus goes back to the Search tab when it goes.
  useEffect(() => {
    document.body.classList.add(LOCK_CLASS);
    return () => {
      document.body.classList.remove(LOCK_CLASS);
      document.querySelector('[data-search-tab]')?.focus({ preventScroll: true });
    };
  }, []);

  useEffect(() => {
    if (!activeRow) return;
    document.getElementById(activeRow.id)?.scrollIntoView?.({ block: 'nearest' });
  }, [activeRow]);

  const cancel = useCallback(() => {
    if (openedFromApp(location.state)) navigate(-1);
    else navigate(withoutSheet(location), { replace: true });
  }, [navigate, location]);

  const openRow = useCallback((row) => navigate(row.path, { replace: true }), [navigate]);
  const hover = useCallback((i) => dispatch({ type: 'hover', index: i }), [dispatch]);

  const searchLibrary = () => {
    const q = state.query.trim();
    navigate(q ? `/library?q=${encodeURIComponent(q)}` : '/library', { replace: true });
  };

  const onInputKeyDown = (event) => {
    if (event.nativeEvent?.isComposing) return;
    const action = interpretKey(event.key, state);
    if (!action || action.kind === 'escape') return; // Esc bubbles to the dialog, which closes it
    event.preventDefault();
    if (action.kind === 'move') {
      dispatch({ type: 'move', delta: action.delta, rowCount: rows.length });
    } else if (activeRow) {
      openRow(activeRow);
    } else {
      searchLibrary();
    }
  };

  const onDialogKeyDown = (event) => {
    if (event.key === 'Escape' && !event.nativeEvent?.isComposing) {
      event.preventDefault();
      cancel();
    }
  };

  const clear = () => {
    setQuery('');
    inputRef.current?.focus();
  };

  return (
    // The dialog only listens for Esc, bubbled from the controls inside it.
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions
    <div
      className="search-sheet"
      role="dialog"
      aria-modal="true"
      aria-label="Search"
      onKeyDown={onDialogKeyDown}
    >
      <div className="search-sheet__bar" role="search">
        <div className="search-sheet__field">
          <SearchIcon size={18} />
          <input
            ref={inputRef}
            type="text"
            role="combobox"
            className="search-sheet__input"
            placeholder="Title, actor or director"
            aria-label="Search the library"
            aria-autocomplete="list"
            aria-expanded={open}
            aria-controls={listboxId}
            aria-activedescendant={activeRow ? activeRow.id : undefined}
            autoComplete="off"
            autoCorrect="off"
            autoCapitalize="off"
            spellCheck={false}
            enterKeyHint="go"
            value={state.query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={onInputKeyDown}
          />
          {state.query === '' ? null : (
            <button
              type="button"
              className="search-sheet__clear"
              aria-label="Clear search"
              onClick={clear}
            >
              <span className="search-sheet__clear-dot" aria-hidden="true">
                ×
              </span>
            </button>
          )}
        </div>
        <button type="button" className="search-sheet__cancel" onClick={cancel}>
          Cancel
        </button>
      </div>
      <p className="sr-only" aria-live="polite" aria-atomic="true" data-testid="search-live">
        {message}
      </p>
      <div className="search-sheet__body">
        {open ? (
          <SearchResults
            size="lg"
            status={status}
            result={result}
            rows={rows}
            activeIndex={state.active}
            listboxId={listboxId}
            onHover={hover}
            onOpen={openRow}
            onSeeAll={(event) => {
              event.preventDefault();
              searchLibrary();
            }}
          />
        ) : null}
      </div>
    </div>
  );
}
