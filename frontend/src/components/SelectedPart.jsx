import { useLayoutEffect } from 'react';
import { playbackChart as copy } from '../state/adminCopy.js';
import { describePlayback, operatorName } from '../state/healthFormat.js';
import { differentPlace, partTimes } from '../state/playbackChart.js';
import { usePlaybackRecord } from '../state/usePlaybackRecord.js';
import { Button } from './Button.jsx';
import PlaybackMinuteStrip from './PlaybackMinuteStrip.jsx';
import { SkeletonBlock } from './Skeleton.jsx';

/**
 * One part of the selected viewing: its minute strip and figures lines, read from its own record, with its own
 * skeleton, "no minutes" text (404) and error with retry. `onRecord` hears each record once it is read. With `named`
 * (a viewing of several parts) its controls carry its times; `first` (`{ environment, operator }` of the first part,
 * for a later part) puts the place at the front of the figures line when this part's differs.
 */
export default function SelectedPart({ part, film, onRecord, first = null, named = false }) {
  const { record, error, loading, reload } = usePlaybackRecord(part.playbackId);
  // Before paint, so the heading never shows without the operator for a frame.
  useLayoutEffect(() => {
    if (record && onRecord) onRecord(record);
  }, [record, onRecord]);

  const times = named ? partTimes(part) : null;
  if (loading) {
    return (
      <div role="status" aria-label={copy.named(copy.loadingStripLabel, times)}>
        <SkeletonBlock className="pchart__strip-skeleton" />
      </div>
    );
  }
  // The record is gone (the table keeps the last 2,000): trying again cannot bring it back.
  if (error?.status === 404) return <p className="admin__text">{copy.noMinutes}</p>;
  if (error) {
    return (
      <div
        className="admin__todo-error"
        role="alert"
        aria-label={times ? copy.stripErrorFor(times) : undefined}
      >
        <p className="admin__todo-error-text">{copy.stripError}</p>
        <Button onClick={reload} aria-label={times ? copy.named(copy.retry, times) : undefined}>
          {copy.retry}
        </Button>
      </div>
    );
  }
  // The record's first line (when, how long, on what) is the heading above; its times lead the next one.
  const [, ...lines] = describePlayback(record);
  const place = first ? differentPlace(part, operatorName(record.report.operator), first) : null;
  const lead = [place, partTimes(part)].filter(Boolean).join(' · ');
  return (
    <>
      <PlaybackMinuteStrip minutes={record.report.minutes} film={film} times={times} />
      {lines.map((line, at) => (
        <p key={line} className="admin__mono pchart__line">
          {at === 0 ? `${lead} · ${line}` : line}
        </p>
      ))}
    </>
  );
}
