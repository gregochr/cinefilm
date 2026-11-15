import { playbackChart as copy } from '../state/adminCopy.js';
import { describePlayback, operatorName } from '../state/healthFormat.js';
import { sessionHeading } from '../state/playbackChart.js';
import { usePlaybackRecord } from '../state/usePlaybackRecord.js';
import { Button } from './Button.jsx';
import PlaybackMinuteStrip from './PlaybackMinuteStrip.jsx';
import { SkeletonBlock } from './Skeleton.jsx';

/**
 * The session picked in the chart: its film and facts at once (they are in the chart's rows), then the per-minute
 * strip and the figures line from the full record, which load on their own with their own skeleton and error.
 */
export default function SelectedSession({ session }) {
  const { record, error, loading, reload } = usePlaybackRecord(session.playbackId);
  const heading = sessionHeading(session, operatorName(record?.report.operator));
  let detail = null;
  if (loading) {
    detail = (
      <div role="status" aria-label={copy.loadingStripLabel}>
        <SkeletonBlock className="pchart__strip-skeleton" />
      </div>
    );
  } else if (error) {
    detail = (
      <div className="admin__todo-error" role="alert">
        <p className="admin__todo-error-text">{copy.stripError}</p>
        <Button onClick={reload}>{copy.retry}</Button>
      </div>
    );
  } else {
    detail = (
      <>
        <PlaybackMinuteStrip minutes={record.report.minutes} film={session.filmTitle} />
        {/* The record's first line (when, how long, on what) is the heading above. */}
        {describePlayback(record)
          .slice(1)
          .map((line) => (
            <p key={line} className="admin__mono pchart__line">
              {line}
            </p>
          ))}
      </>
    );
  }
  return (
    <section className="pchart__selected" aria-label={copy.selectedLabel}>
      <p className="pchart__heading">
        <strong className="pchart__film">{session.filmTitle}</strong>
        {heading.map((part) => ` · ${part}`).join('')}
      </p>
      {detail}
    </section>
  );
}
