import { useCallback, useEffect, useId, useRef } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useSearchIndex } from '../state/searchIndex.js';
import {
  announcement,
  hasQuery,
  interpretKey,
  useSearchCombobox,
} from '../state/useSearchCombobox.js';
import { SearchIcon } from './icons.jsx';
import SearchResults from './SearchResults.jsx';

/**
 * The header search: a combobox over the shared client-side index, with the results in a dropdown under it. Every
 * keystroke searches at once (no debounce, no request). Enter opens the active row (the first, until you move);
 * with nothing to open, or when the index failed to load, it searches the Library as the old box did.
 */
export default function SearchBox() {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const { index, status } = useSearchIndex();
  const { state, result, rows, dispatch, setQuery } = useSearchCombobox(index);
  const inputRef = useRef(null);
  const panelRef = useRef(null);
  const listboxId = useId();
  const open = state.open;
  const activeRow = open && status === 'ready' ? (rows[state.active] ?? null) : null;

  // Keep the active row visible as the arrows move it.
  useEffect(() => {
    if (!activeRow) return;
    panelRef.current
      ?.querySelector(`[id="${activeRow.id}"]`)
      ?.scrollIntoView?.({ block: 'nearest' });
  }, [activeRow]);

  // A navigation from anywhere else (the nav links stay clickable over the scrim) closes the dropdown.
  useEffect(() => {
    dispatch({ type: 'close' });
  }, [pathname, dispatch]);

  const finish = useCallback(() => {
    dispatch({ type: 'clear' });
    inputRef.current?.blur();
  }, [dispatch]);

  const openRow = useCallback(
    (row) => {
      navigate(row.path);
      finish();
    },
    [navigate, finish],
  );
  const hover = useCallback((i) => dispatch({ type: 'hover', index: i }), [dispatch]);

  const searchLibrary = () => {
    const q = state.query.trim();
    navigate(q ? `/library?q=${encodeURIComponent(q)}` : '/library');
    finish();
  };

  const onKeyDown = (event) => {
    if (event.nativeEvent?.isComposing) return;
    const action = interpretKey(event.key, state);
    if (!action) return;
    event.preventDefault();
    if (action.kind === 'move') {
      dispatch({ type: 'move', delta: action.delta, rowCount: rows.length });
    } else if (action.kind === 'enter') {
      if (activeRow) openRow(activeRow);
      else searchLibrary();
    } else if (action.blur) {
      inputRef.current?.blur();
    } else {
      dispatch({ type: 'clear' });
    }
  };

  const message = announcement({ open, status, result });

  return (
    <div className="header__search" role="search">
      <div className="searchbox">
        <SearchIcon size={16} />
        <input
          ref={inputRef}
          type="text"
          role="combobox"
          className="searchbox__input"
          placeholder="Title, actor or director"
          aria-label="Search the library"
          aria-autocomplete="list"
          aria-expanded={open}
          aria-controls={listboxId}
          aria-activedescendant={activeRow ? activeRow.id : undefined}
          autoComplete="off"
          spellCheck={false}
          value={state.query}
          onChange={(e) => setQuery(e.target.value)}
          onFocus={() => dispatch({ type: 'open' })}
          onKeyDown={onKeyDown}
          data-global-search
        />
        <kbd className="searchbox__hint" aria-hidden="true" data-testid="search-hint">
          {open ? 'Esc' : '/'}
        </kbd>
      </div>
      <p className="sr-only" aria-live="polite" data-testid="search-live">
        {message}
      </p>
      {open && hasQuery(state.query) ? (
        <>
          <div
            className="search-scrim"
            role="presentation"
            data-testid="search-scrim"
            onMouseDown={() => dispatch({ type: 'close' })}
          />
          {/* Keep the focus in the input when the panel is pressed. */}
          <div
            className="search-panel"
            role="presentation"
            ref={panelRef}
            onMouseDown={(e) => e.preventDefault()}
          >
            <SearchResults
              status={status}
              result={result}
              rows={rows}
              activeIndex={state.active}
              listboxId={listboxId}
              onHover={hover}
              onOpen={openRow}
              onSeeAll={finish}
            />
          </div>
        </>
      ) : null}
    </div>
  );
}
