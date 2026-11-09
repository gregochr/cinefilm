import { Link } from 'react-router-dom';
import { displayName } from '../state/collections.js';
import CollectionFan from './CollectionFan.jsx';

/**
 * A collection folded into the Films grid: poster-sized, with the poster fan, a count badge, the
 * name over a scrim and "Collection · 4 films" underneath. `films` are the folded films (earliest first).
 */
export default function CollectionTile({ collection, films }) {
  const name = displayName(collection.name);
  const count = films.length;
  const noun = count === 1 ? 'film' : 'films';
  return (
    <Link
      to={`/collections/${collection.id}`}
      className="poster collection-tile"
      aria-label={`${name}, collection of ${count} ${noun}`}
    >
      <div className="art art--poster collection-tile__art">
        <CollectionFan
          posters={films.map((f) => f.posterPath)}
          seed={collection.id}
          name={collection.name}
        />
        <span className="collection-tile__badge" aria-hidden="true">
          {count}
        </span>
        <span className="collection-tile__scrim" aria-hidden="true">
          <span className="collection-tile__name">{name}</span>
        </span>
      </div>
      <span className="poster__caption">
        Collection · {count} {noun}
      </span>
    </Link>
  );
}
