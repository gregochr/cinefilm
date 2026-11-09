import { Link } from 'react-router-dom';
import { captionTitle, labelTitle } from '../state/titles.js';
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

