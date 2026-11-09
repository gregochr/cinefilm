import { Link } from 'react-router-dom';
import { imageUrl } from '../api/images.js';
import { placeholderColor } from './placeholder.js';

/**
 * Collection card: one poster per owned film (up to three) fanned out, the earliest film at the front, plus the
 * name and "2 of 4 films". Posters render back-to-front so the front one is painted last.
 */
export default function CollectionCard({ collection }) {
  const paths = collection.posterPaths?.length
    ? collection.posterPaths.slice(0, 3)
    : [collection.posterPath ?? null];
  const n = paths.length;
  // paths[0] is depth 0 (front); reverse so DOM order is back-to-front.
  const posters = paths.map((path, depth) => ({ path, depth })).reverse();
  const count =
    collection.totalCount != null
      ? `${collection.ownedCount} of ${collection.totalCount} films`
      : `${collection.ownedCount ?? 0} films`;
  return (
    <Link to={`/collections/${collection.id}`} className="collection-card">
      <span className={`collection-card__fan collection-card__fan--${n}`} aria-hidden="true">
        {posters.map(({ path, depth }) => {
          const url = imageUrl(path);
          const style = url
            ? { backgroundImage: `url(${url})`, backgroundSize: 'cover' }
            : { background: placeholderColor(`${collection.id}-${depth}`, collection.name) };
          return (
            <span
              key={depth}
              className={`collection-card__poster collection-card__poster--depth-${depth}`}
              style={style}
            />
          );
        })}
      </span>
      <span className="collection-card__name">{collection.name}</span>{' '}
      <span className="collection-card__count">{count}</span>
    </Link>
  );
}
