import { useLayoutEffect } from 'react';
import { playbackChart as copy } from '../state/adminCopy.js';
import { describePlayback } from '../state/healthFormat.js';
import { partTimes } from '../state/playbackChart.js';
import { usePlaybackRecord } from '../state/usePlaybackRecord.js';
import { Button } from './Button.jsx';
import PlaybackMinuteStrip from './PlaybackMinuteStrip.jsx';
import { SkeletonBlock } from './Skeleton.jsx';

/**
 * One part of the selected viewing: its minute strip and figures lines, read from its own record, with its own
 * skeleton, "no minutes" text (404) and error with retry. `onRecord` hears each record once it is read.
 */
export default function SelectedPart({ part, film, onRecord }) {
  const { record, error, loading, reload } = usePlaybackRecord(part.playbackId);
  // Before paint, so the heading never shows without the operator for a frame.
  useLayoutEffect(() => {
    if (record && onRecord) onRecord(record);
  }, [record, onRecord]);

  if (loading) {
    return (
      <div role="status" aria-label={copy.loadingStripLabel}>
        <SkeletonBlock className="pchart__strip-skeleton" />
      </div>
    );
  }
  // The record is gone (the table keeps the last 2,000): trying again cannot bring it back.
  if (error?.status === 404) return <p className="admin__text">{copy.noMinutes}</p>;
  if (error) {
    return (
      <div className="admin__todo-error" role="alert">
        <p className="admin__todo-error-text">{copy.stripError}</p>
        <Button onClick={reload}>{copy.retry}</Button>
      </div>
    );
  }
  // The record's first line (when, how long, on what) is the heading above; its times lead the next one.
  const [, ...lines] = describePlayback(record);
  return (
    <>
      <PlaybackMinuteStrip minutes={record.report.minutes} film={film} />
      {lines.map((line, at) => (
        <p key={line} className="admin__mono pchart__line">
          {at === 0 ? `${partTimes(part)} · ${line}` : line}
        </p>
      ))}
    </>
  );
}
