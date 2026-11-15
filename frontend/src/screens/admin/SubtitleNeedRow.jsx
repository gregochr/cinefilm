import { useRef, useState } from 'react';
import { ActionWithHint } from '../../components/ActionWithHint.jsx';
import { buttons } from '../../state/adminCopy.js';
import {
  foreignActions,
  foreignLine,
  forcedActions,
  forcedRowLine,
  filmTitle,
  pickerButton,
} from '../../state/adminSubtitles.js';
import AddOwnFile from './AddOwnFile.jsx';
import RowText from './AdminRowText.jsx';

/**
 * One film the server could not settle. The foreign tab offers "Search for English subtitles…" (or, for unlabelled
 * audio, "It's in {language} — find subtitles"), "Add my own file…" (the attach flow for this film) and, for unlabelled
 * audio, "It's English"; the forced tab offers "Find forced subtitles…" and "No foreign scenes". The search buttons
 * open the OpenSubtitles picker through `onFind(row, opener)` and are left out when the row has no `editionId` (no
 * edition to search for). Both owner choices save `NOT_NEEDED` through `onChoose(row)`; while a row's choice is saving
 * its buttons are `aria-disabled` (still focusable) and a click does nothing. `problem` is what a failed save says.
 */
export default function SubtitleNeedRow({
  row,
  kind,
  saving,
  problem,
  onChoose,
  onAttached,
  onFind,
}) {
  const [adding, setAdding] = useState(false);
  const addRef = useRef(null);
  const busy = { 'aria-disabled': saving || undefined };
  const choose = () => {
    if (!saving) onChoose(row);
  };
  const closeForm = () => {
    setAdding(false);
    addRef.current?.focus();
  };
  const find = (event) => {
    if (!saving) onFind(row, event.currentTarget);
  };
  const actions = kind === 'foreign' ? foreignActions(row) : forcedActions(row);
  return (
    <li className="admin__todo-row">
      <RowText row={row} line={kind === 'foreign' ? foreignLine(row) : forcedRowLine(row)} />
      <div className="admin__actions admin__todo-actions">
        {actions.map((action) => {
          if (action === 'addFile') {
            return (
              <ActionWithHint
                key={action}
                ref={addRef}
                hint={buttons.addFileToFilm.hint}
                aria-expanded={adding}
                onClick={() => setAdding(!adding)}
              >
                {buttons.addFileToFilm.label}
              </ActionWithHint>
            );
          }
          if (action !== 'itsEnglish' && action !== 'noForeignScenes') {
            const { label, hint } = pickerButton(action, row);
            return (
              <ActionWithHint
                key={action}
                hint={hint}
                {...busy}
                aria-haspopup="dialog"
                onClick={find}
              >
                {label}
              </ActionWithHint>
            );
          }
          const entry = action === 'itsEnglish' ? buttons.itsEnglish : buttons.noForeignScenes;
          return (
            <ActionWithHint key={action} hint={entry.hint} {...busy} onClick={choose}>
              {entry.label}
            </ActionWithHint>
          );
        })}
      </div>
      {problem ? (
        <p className="admin__todo-problem" role="alert">
          {problem}
        </p>
      ) : null}
      {adding ? (
        <AddOwnFile
          filmId={row.filmId}
          title={filmTitle(row)}
          onAttached={() => onAttached(row)}
          onCancel={closeForm}
        />
      ) : null}
    </li>
  );
}
