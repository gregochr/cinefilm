import { Link } from 'react-router-dom';
import { imageUrl } from '../api/images.js';
import { placeholderColor } from './placeholder.js';

/** Collection card with three fanned posters, the name and "2 of 4 films". */
export default function CollectionCard({ collection }) {
  const paths = collection.posterPaths?.length
    ? collection.posterPaths.slice(0, 3)
    : [collection.posterPath, null, null];
  while (paths.length < 3) paths.push(null);
  const count =
    collection.totalCount != null
      ? `${collection.ownedCount} of ${collection.totalCount} films`
      : `${collection.ownedCount ?? 0} films`;
  return (
    <Link to={`/collections/${collection.id}`} className="collection-card">
      <span className="collection-card__fan" aria-hidden="true">
        {paths.map((path, i) => {
          const url = imageUrl(path);
          const style = url
            ? { backgroundImage: `url(${url})`, backgroundSize: 'cover' }
            : { background: placeholderColor(`${collection.id}-${i}`, collection.name) };
          return (
            <span
              key={i}
              className={`collection-card__poster collection-card__poster--${i}`}
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
