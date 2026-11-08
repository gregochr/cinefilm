import { Link } from 'react-router-dom';
import { captionTitle, labelTitle } from '../state/titles.js';
import { formatLeft } from '../player/time.js';
import Art from './Art.jsx';
import { CheckIcon } from './icons.jsx';

/** A 2:3 poster tile linking to the film, with the "Title · Year" caption. */
export function Poster({ film, caption }) {
  const label = `${labelTitle(film.title, film.year)}${film.watched ? ', watched' : ''}`;
  return (
    <Link to={`/films/${film.id}`} className="poster" aria-label={label}>
      <Art kind="poster" path={film.posterPath} id={film.id} title={film.title}>
        {film.watched ? (
          <span className="poster__watched" title="Watched">
            <CheckIcon size={13} />
          </span>
        ) : null}
      </Art>
      <span className="poster__caption">{captionTitle(film.title, film.year)}</span>
      {caption ? <span className="poster__role">{caption}</span> : null}
    </Link>
  );
}

/** A 16:9 "continue watching" card with a progress fill and time left. */
export function ContinueCard({ film }) {
  const progress = film.progress;
  const pct = progress?.runtimeMs
    ? Math.round((progress.positionMs / progress.runtimeMs) * 100)
    : 0;
  const left = progress ? formatLeft(progress.positionMs, progress.runtimeMs) : '';
  return (
    <Link to={`/films/${film.id}`} className="continue-card" aria-label={`${film.title}, ${left}`}>
      <Art kind="still" path={film.backdropPath} id={film.id} title={film.title}>
        <span className="continue-card__track" aria-hidden="true">
          <span
            className="continue-card__fill"
            data-testid="continue-fill"
            style={{ width: `${pct}%` }}
          />
        </span>
      </Art>
      <span className="continue-card__meta">
        <span className="continue-card__title">{film.title}</span>
        <span className="continue-card__left">{left}</span>
      </span>
    </Link>
  );
}
