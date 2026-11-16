import { Link } from 'react-router-dom';
import { cueTime, displayName, playFromHereMs, playerPath } from '../state/subtitleAdmin.js';
import { useSubtitleCues } from '../state/useSubtitles.js';
import { SkeletonBlock } from './Skeleton.jsx';
import { ErrorState } from './StateMessages.jsx';

/**
 * The cues of one file with "Play from here" (the player opens 3 s before the cue). Cue text is untrusted and is only
 * ever rendered as text. Used by Film detail's Fix… panel ("Preview lines").
 */
export default function SubtitleCueList({ item, id }) {
  const { data, error, loading, reload } = useSubtitleCues(item);
  if (loading && !data) {
    return (
      <div role="status" aria-label="Loading cues">
        <SkeletonBlock style={{ height: 80 }} />
      </div>
    );
  }
  const failure = error ? (
    <ErrorState onRetry={reload}>Couldn&rsquo;t load the cues.</ErrorState>
  ) : null;
  if (!data) return failure;
  if (data.cues.length === 0) {
    return (
      <>
        {failure}
        <p className="admin__text">This file has no cues.</p>
      </>
    );
  }
  return (
    <>
      {failure}
      <ol className="subs__cues" id={id} aria-label={`Cues in ${displayName(item)}`}>
        {data.cues.map((cue, i) => (
          <li key={`${i}-${cue.startMs}`} className="subs__cue">
            <span className="admin__mono subs__time">{cueTime(cue)}</span>
            <span className="subs__text">{cue.text}</span>
            {item.filmId == null ? null : (
              <Link
                className="admin__link"
                to={playerPath(item, playFromHereMs(cue))}
                aria-label={`Play from here: ${cue.text.replace(/\s+/g, ' ')}`}
              >
                Play from here
              </Link>
            )}
          </li>
        ))}
      </ol>
    </>
  );
}
