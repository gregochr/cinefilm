import { journeyTrainRemove as copy } from '../../state/journeysCopy.js';
import { trainRemoveSentence, trainRemoveSpoken } from '../../state/journeysFormat.js';
import RowConfirm from './RowConfirm.jsx';

/** The id of a row's "Remove train…", for the page to put focus on it. */
export const removeTrainButtonId = (journeyId) => `admin-journey-remove-train-${journeyId}`;

/**
 * A stored journey's "Remove train…" and its inline confirmation (`RowConfirm`, with the removal's words), for a
 * journey that has a train. The page owns which group is open (`open`), the request (`busy`) and a refusal (`problem`,
 * its sentence, or null).
 */
export default function JourneyTrainRemove({
  journey,
  open,
  busy,
  problem,
  onOpen,
  onKeep,
  onConfirm,
}) {
  return (
    <RowConfirm
      opener={{
        id: removeTrainButtonId(journey.id),
        className: 'admin__actions admin__todo-actions journey-row__remove',
        label: copy.open,
        name: trainRemoveSpoken(journey),
      }}
      confirmClassName="journey-remove__confirm"
      words={{
        group: copy.group,
        sentence: trainRemoveSentence(journey),
        confirm: copy.confirm,
        busy: copy.removing,
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
