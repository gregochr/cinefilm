import { useId } from 'react';
import { Link } from 'react-router-dom';
import { formatLeft } from '../player/time.js';
import { captionTitle, labelTitle } from '../state/titles.js';
import Art from './Art.jsx';
import { CheckIcon } from './icons.jsx';

/**
 * A 2:3 poster tile linking to the film, with the "Title · Year" caption. A film that is in progress (only
 * `film.inProgress` says so, never `film.progress`, which a film below the minimum watch time also has) also shows a
 * progress fill along the bottom edge and "time left" under the caption.
 */
export function Poster({ film, caption, linkRef }) {
  const leftId = useId();
  const progress = film.inProgress ? film.progress : null;
  const left = progress ? formatLeft(progress.positionMs, progress.runtimeMs) : null;
  const pct = progress?.runtimeMs
    ? Math.min(100, Math.round((progress.positionMs / progress.runtimeMs) * 100))
    : 0;
  const label = `${labelTitle(film.title, film.year)}${film.watched ? ', watched' : ''}`;
  return (
    <Link
      to={`/films/${film.id}`}
      className="poster"
      aria-label={label}
      aria-describedby={left ? leftId : undefined}
      ref={linkRef}
    >
      <Art kind="poster" path={film.posterPath} id={film.id} title={film.title}>
        {film.watched ? (
          <span className="poster__watched" title="Watched">
            <CheckIcon size={13} />
          </span>
        ) : null}
        {film.inProgress ? (
          <span className="continue-card__track" aria-hidden="true">
            <span
              className="continue-card__fill"
              data-testid="poster-fill"
              style={{ width: `${pct}%` }}
            />
          </span>
        ) : null}
      </Art>
      <span className="poster__caption">{captionTitle(film.title, film.year)}</span>
      {left ? (
        <span id={leftId} className="poster__left">
          {left}
        </span>
      ) : null}
      {caption ? <span className="poster__role">{caption}</span> : null}
    </Link>
  );
}
