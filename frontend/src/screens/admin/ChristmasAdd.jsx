import { useCallback, useEffect, useId, useRef } from 'react';
import { SearchIcon } from '../../components/icons.jsx';
import { addResultRow } from '../../state/christmas.js';
import { useSearchIndex } from '../../state/searchIndex.js';
import { useAddFilm } from '../../state/useAddFilm.js';
import { hasQuery } from '../../state/useSearchCombobox.js';
import { useSignInRecovery } from './useSignInRecovery.js';
import ChristmasAddResults, { headText } from './ChristmasAddResults.jsx';
import ChristmasAddSheet from './ChristmasAddSheet.jsx';

/** What the panel leaves open when a press or focus lands there: the toast (its Undo) and a dialog (Fix match). */
const BELONGS = '.xmas-toast-region, [aria-modal="true"], .ee';
/** A dialog open anywhere (Fix match, with its backdrop) owns presses: one on its backdrop is not "outside". */
const DIALOG_OPEN = '[aria-modal="true"], .ee';

/**
 * "Add a film": a search field that finds any film in the library and offers the one action each result has. On a
 * desktop the results are a panel under the field: a plain list of rows with real buttons (so not a listbox), and the
 * keyboard moves real focus. ArrowDown from the field goes to the first result's button, the arrows move between
 * buttons, ArrowUp from the first goes back to the field, and Esc from any of them closes the panel and focuses the
 * field. The panel also closes when focus leaves it for something else. At phone width the field opens a full-screen
 * sheet (`sheetOpen` / `onSheetOpenChange` are the screen's, and `phone` too, so it decides in the same render where
 * the toast goes: `toast` is shown inside the sheet while it is up). An action goes through the screen's write path
 * (`onAct`), the row updates in place and the panel stays open. `onFix(film, opener)` opens Fix match for a film with
 * no TMDB id; `refreshKey` changes when a match was fixed; `version` and `issued` are the screen data's request
 * numbers (see `resultState`). `onPanelChange(open)` says when the panel opens or closes (the toast waits meanwhile).
 */
export default function ChristmasAdd({
  data,
  version,
  issued,
  busy,
  onAct,
  onFix,
  registerButton,
  refreshKey,
  toast,
  phone,
  sheetOpen,
  onSheetOpenChange,
  onPanelChange,
}) {
  const { index, status } = useSearchIndex();
  const uid = useId();
  const recovered = useSignInRecovery();
  const add = useAddFilm({
    index,
    status,
    data,
    version,
    issued,
    idPrefix: `${uid}-row`,
    refreshKey,
    recovered,
  });
  const { state, dispatch, setQuery, rows } = add;
  const wrapper = useRef(null);
  const panel = useRef(null);
  const input = useRef(null);
  const trigger = useRef(null);
  const showing = state.open && hasQuery(state.query) && !phone;
  const sheetShown = sheetOpen && phone;

  // A press outside the field and its panel closes the panel (the query stays).
  useEffect(() => {
    if (!showing) return undefined;
    const onDown = (event) => {
      if (event.target.closest?.(BELONGS) || document.querySelector(DIALOG_OPEN)) return;
      if (!wrapper.current?.contains(event.target)) dispatch({ type: 'close' });
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [showing, dispatch]);

  useEffect(() => {
    onPanelChange?.(showing);
    return () => onPanelChange?.(false);
  }, [showing, onPanelChange]);

  // Widening the window past phone width while the sheet is up: it goes, and focus goes to the field (the sheet had it).
  useEffect(() => {
    if (phone || !sheetOpen) return;
    onSheetOpenChange(false);
    input.current?.focus();
  }, [phone, sheetOpen, onSheetOpenChange]);

  const act = useCallback(
    (row, opener) => {
      const view = addResultRow(row.state);
      if (!view) return;
      if (view.action.fix) {
        // On a phone the sheet closes with this (and the search is cleared, as Cancel does), so Fix match hands focus
        // back to the button that opened the sheet.
        onFix({ filmId: row.filmId, title: row.title }, phone ? trigger.current : opener);
        if (phone) {
          onSheetOpenChange(false);
          dispatch({ type: 'clear' });
        }
      } else {
        onAct(row);
      }
    },
    [onAct, onFix, phone, onSheetOpenChange, dispatch],
  );

  const closeSheet = () => {
    onSheetOpenChange(false);
    dispatch({ type: 'clear' });
    trigger.current?.focus();
  };

  const onKeyDown = (event) => {
    if (event.nativeEvent?.isComposing) return;
    if (event.key === 'Escape') {
      if (!showing) return;
      event.preventDefault();
      // Focus first: the field opens the panel when it gains focus, and the close must come after that.
      input.current?.focus();
      dispatch({ type: 'close' });
      return;
    }
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    const delta = event.key === 'ArrowDown' ? 1 : -1;
    if (event.target === input.current) {
      if (delta < 0) return;
      event.preventDefault();
      if (!showing) dispatch({ type: 'open' });
      else panel.current?.querySelector('button')?.focus();
      return;
    }
    // On a result's button: the next or the one before, and the field above the first.
    event.preventDefault();
    const buttons = [...panel.current.querySelectorAll('button')];
    const at = buttons.indexOf(event.target) + delta;
    if (at < 0) input.current?.focus();
    else buttons[at]?.focus();
  };

  // Focus leaving the field and its panel for something else (Tab past the last result) closes the panel. Focus
  // going nowhere in particular (null) is a press on the page, which the press handler above deals with.
  const onBlur = (event) => {
    const to = event.relatedTarget;
    if (!to || wrapper.current?.contains(to) || to.closest?.(BELONGS)) return;
    dispatch({ type: 'close' });
  };

  if (phone) {
    return (
      <div className="xmas-add">
        <button
          ref={trigger}
          type="button"
          className="xmas-add__field xmas-add__field--button"
          aria-haspopup="dialog"
          aria-expanded={sheetShown}
          onClick={() => onSheetOpenChange(true)}
        >
          <SearchIcon size={16} />
          <span>Add a film</span>
        </button>
        {sheetShown ? (
          <ChristmasAddSheet
            add={add}
            head={headText(add)}
            busy={busy}
            onAct={act}
            registerButton={registerButton}
            onClose={closeSheet}
            toast={toast}
          />
        ) : null}
      </div>
    );
  }

  return (
    // The wrapper only watches focus leave it; the field and the buttons inside do the work.
    <div className="xmas-add" role="search" ref={wrapper} onBlur={onBlur}>
      <div className="xmas-add__field">
        <SearchIcon size={16} />
        <input
          ref={input}
          type="text"
          className="xmas-add__input"
          placeholder="Add a film"
          aria-label="Add a film"
          autoComplete="off"
          spellCheck={false}
          value={state.query}
          onChange={(event) => setQuery(event.target.value)}
          onFocus={() => dispatch({ type: 'open' })}
          onClick={() => dispatch({ type: 'open' })}
          onKeyDown={onKeyDown}
        />
      </div>
      {showing ? (
        // The arrows and Esc are heard from the input and from a button inside the panel.
        // eslint-disable-next-line jsx-a11y/no-static-element-interactions
        <div className="xmas-add__panel" ref={panel} onKeyDown={onKeyDown}>
          <ChristmasAddResults
            head={headText(add)}
            rows={add.ready ? rows : []}
            busy={busy}
            statesFailed={add.statesFailed}
            onAct={act}
            registerButton={registerButton}
          />
        </div>
      ) : null}
    </div>
  );
}
