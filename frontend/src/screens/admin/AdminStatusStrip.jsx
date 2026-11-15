import { Link } from 'react-router-dom';
import { Button } from '../../components/Button.jsx';
import { SkeletonBlock } from '../../components/Skeleton.jsx';
import { statusStrip } from '../../state/adminCopy.js';
import { libraryCard, playbackCard, playingNowCard } from '../../state/adminSummaryFormat.js';

function Card({ eyebrow, text, failed = false, to, link, phone = false }) {
  return (
    <li className={`admin__strip-card${phone ? '' : ' admin__strip-card--wide-only'}`}>
      <p className="admin__strip-eyebrow">{eyebrow}</p>
      <p className={`admin__strip-text${failed ? ' admin__strip-text--error' : ''}`}>{text}</p>
      <Link to={to} className="admin__strip-link">
        {link}
      </Link>
    </li>
  );
}

/**
 * The three status cards under the To do intro (Library, Playback, Playing now), each linking to its section.
 * Phones show only the Library card (a CSS rule on `admin__strip-card--wide-only`, AR6). A static skeleton holds the
 * card shapes until the first summary arrives (a later re-read keeps the cards on screen); a failed read is an inline error banner (never amber) with a retry.
 * `now` (epoch ms) is the moment "scanned {ago}" counts from.
 */
export default function AdminStatusStrip({ summary, error, onRetry, now = Date.now() }) {
  if (error && !summary) {
    return (
      <div className="admin__strip-error" role="alert">
        <p className="admin__strip-error-text">{statusStrip.loadError}</p>
        <Button onClick={onRetry}>{statusStrip.retry}</Button>
      </div>
    );
  }
  if (!summary) {
    return (
      <div className="admin__strip" role="status" aria-label="Loading the status">
        <SkeletonBlock className="admin__strip-skeleton" />
        <SkeletonBlock className="admin__strip-skeleton admin__strip-card--wide-only" />
        <SkeletonBlock className="admin__strip-skeleton admin__strip-card--wide-only" />
      </div>
    );
  }
  const library = libraryCard(summary.library, now);
  return (
    <ul className="admin__strip" aria-label="Status">
      <Card
        phone
        eyebrow={statusStrip.library.eyebrow}
        text={library.text}
        failed={library.failed}
        to="/admin/library"
        link={statusStrip.library.link}
      />
      <Card
        eyebrow={statusStrip.playback.eyebrow}
        text={playbackCard(summary.playback)}
        to="/admin/playback"
        link={statusStrip.playback.link}
      />
      <Card
        eyebrow={statusStrip.playingNow.eyebrow}
        text={playingNowCard(summary.playingNow)}
        to="/admin/playback"
        link={statusStrip.playingNow.link}
      />
    </ul>
  );
}
