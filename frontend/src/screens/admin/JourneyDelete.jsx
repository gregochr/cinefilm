import { journeyDelete as copy } from '../../state/journeysCopy.js';
import { deleteSentence, deleteSpoken } from '../../state/journeysFormat.js';
import RowConfirm from './RowConfirm.jsx';

/** The id of a row's "Delete…", for the page to put focus on it once a neighbour has gone. */
export const deleteButtonId = (journeyId) => `admin-journey-delete-${journeyId}`;

/**
 * A stored journey's "Delete…" and its inline confirmation (`RowConfirm`, with the delete's words). The page owns which
 * group is open (`open`), the request (`busy`) and a refusal (`problem`, its sentence, or null).
 */
export default function JourneyDelete({ journey, open, busy, problem, onOpen, onKeep, onConfirm }) {
  return (
    <RowConfirm
      opener={{
        id: deleteButtonId(journey.id),
        className: 'admin__actions admin__todo-actions journey-row__delete',
        label: copy.open,
        name: deleteSpoken(journey),
      }}
      confirmClassName="journey-delete__confirm"
      words={{
        group: copy.group,
        sentence: deleteSentence(journey),
        confirm: copy.confirm,
        busy: copy.deleting,
        keep: copy.keep,
      }}
      open={open}
      busy={busy}
      problem={problem}
      onOpen={onOpen}
      onKeep={onKeep}
      onConfirm={onConfirm}
    />
  );
}
