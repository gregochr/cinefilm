import { ratingItems } from '../state/ratings.js';

function Figures({ item }) {
  return (
    <>
      <span className="ratings__label">{item.label}</span>
      <span className="ratings__score">{item.score}</span>
      {item.suffix ? <span className="ratings__suffix">{item.suffix}</span> : null}
    </>
  );
}

/**
 * "IMDB 8.3 /10    ROTTEN TOMATOES 84%": text only, no logos. Each source with a page opens it in a new tab;
 * a source with no score is hidden, and so is the whole row when neither has one.
 */
export default function RatingsRow({ ratings }) {
  const items = ratingItems(ratings);
  if (items.length === 0) return null;
  return (
    <ul className="ratings" aria-label="Ratings">
      {items.map((item) => (
        <li key={item.key} className="ratings__item">
          {item.href ? (
            <a
              className="ratings__link"
              href={item.href}
              target="_blank"
              rel="noopener noreferrer"
              aria-label={item.accessibleName}
            >
              <Figures item={item} />
            </a>
          ) : (
            <span className="ratings__link">
              <Figures item={item} />
            </span>
          )}
        </li>
      ))}
    </ul>
  );
}
