import { useEffect, useLayoutEffect, useRef } from 'react';
import { SearchIcon } from '../../components/icons.jsx';
import { containTab } from '../../components/useDialog.js';
import { hasQuery } from '../../state/useSearchCombobox.js';
import ChristmasAddResults from './ChristmasAddResults.jsx';
import { useSignInRecovery } from './useSignInRecovery.js';

/**
 * The phone's "Add a film": a full-screen sheet with the field, Cancel and the results (design `06`). The state is the
 * screen's `useAddFilm` (`add`), so the query is the one the field started. The results are a plain list of rows with
 * real buttons. One tap on a result acts and the sheet stays open. Esc and Cancel close it (`onClose`), Tab stays
 * inside it. `toast` is the screen's toast, rendered inside the dialog: a modal dialog hides what is outside it from a
 * screen reader and from the Tab trap, and the toast's Undo must stay reachable. The results hold the live status line.
 */
export default function ChristmasAddSheet({
  add,
  head,
  busy,
  onAct,
  registerButton,
  onClose,
  toast,
}) {
  const input = useRef(null);
  const dialog = useRef(null);
  const signedIn = useSignInRecovery();
  const { state, rows, setQuery } = add;
  const open = hasQuery(state.query);

  useLayoutEffect(() => {
    input.current?.focus();
  }, []);

  // After a 401 the sign-in card takes focus and then goes: put it back inside the sheet, or Esc and the Tab trap
  // would no longer reach it.
  useEffect(() => {
    if (signedIn === 0) return;
    if (!dialog.current?.contains(document.activeElement)) input.current?.focus();
  }, [signedIn]);

  // Nothing behind scrolls while the sheet is up.
  useEffect(() => {
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  }, []);

  const onDialogKeyDown = (event) => {
    if (event.key === 'Escape' && !event.nativeEvent?.isComposing) {
      event.preventDefault();
      onClose();
      return;
    }
    containTab(event, event.currentTarget);
  };

  return (
    // The dialog only listens for Esc and Tab, bubbled from the controls inside it.
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions
    <div
      ref={dialog}
      className="xmas-add-sheet"
      role="dialog"
      aria-modal="true"
      aria-label="Add a film"
      onKeyDown={onDialogKeyDown}
    >
      <div className="xmas-add-sheet__bar" role="search">
        <div className="xmas-add__field">
          <SearchIcon size={18} />
          <input
            ref={input}
            type="text"
            className="xmas-add__input"
            placeholder="Add a film"
            aria-label="Add a film"
            autoComplete="off"
            autoCorrect="off"
            autoCapitalize="off"
            spellCheck={false}
            enterKeyHint="search"
            value={state.query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </div>
        <button type="button" className="xmas-add-sheet__cancel" onClick={onClose}>
          Cancel
        </button>
      </div>
      <div className="xmas-add-sheet__body">
        {open ? (
          <ChristmasAddResults
            head={head}
            rows={add.ready ? rows : []}
            busy={busy}
            statesFailed={add.statesFailed}
            onAct={onAct}
            registerButton={registerButton}
          />
        ) : null}
      </div>
      {toast}
    </div>
  );
}
