import { Fragment, useCallback, useState } from 'react';
import { playbackChart as copy } from '../state/adminCopy.js';
import { operatorName } from '../state/healthFormat.js';
import { partGap, sessionHeading, sessionParts } from '../state/playbackChart.js';
import SelectedPart from './SelectedPart.jsx';

/**
 * The viewing picked in the chart: its film and facts at once (they are in the chart's rows), then each part in
 * order, with the quiet line between two parts. Every part reads its own record.
 */
export default function SelectedSession({ session }) {
  const parts = sessionParts(session);
  const id = session.playbackId;
  // The operator is the first part's record's; it is only used while it belongs to the viewing on show.
  const [heard, setHeard] = useState({ id: null, operator: null });
  const operator = heard.id === id ? heard.operator : null;
  const onFirstRecord = useCallback(
    (record) => setHeard({ id, operator: operatorName(record.report.operator) }),
    [id],
  );
  const heading = sessionHeading(session, operator);
  return (
    <section className="pchart__selected" aria-label={copy.selectedLabel}>
      <p className="pchart__heading">
        <strong className="pchart__film">{session.filmTitle}</strong>
        {heading.map((part) => ` · ${part}`).join('')}
      </p>
      {parts.map((part, at) => {
        const gap = at > 0 ? partGap(parts[at - 1], part) : null;
        return (
          <Fragment key={part.playbackId}>
            {gap && <p className="admin__text pchart__gap">{gap}</p>}
            <SelectedPart
              part={part}
              film={session.filmTitle}
              onRecord={at === 0 ? onFirstRecord : undefined}
            />
          </Fragment>
        );
      })}
    </section>
  );
}
