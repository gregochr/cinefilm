import { Link } from 'react-router-dom';
import { displayName, isComplete } from '../state/collections.js';
import { CheckIcon } from './icons.jsx';
import CollectionFan from './CollectionFan.jsx';

/** A Collections-tab card: the poster fan on a surface, the name and "2 of 4 films". */
export default function CollectionCard({ collection }) {
  const name = displayName(collection.name);
  const complete = isComplete(collection);
  return (
    <Link to={`/collections/${collection.id}`} className="collection-card">
      <span className="collection-card__art">
        <CollectionFan
          posters={collection.posterPaths}
          seed={collection.id}
          name={collection.name}
        />
      </span>
      <span className="collection-card__name">{name}</span>{' '}
      <span className="collection-card__count">
        {collection.ownedCount} of {collection.totalCount} films
        {complete ? (
          <>
            {' '}
            <span className="collection-card__complete">
              <CheckIcon size={13} /> Complete
            </span>
          </>
        ) : null}
      </span>
    </Link>
  );
}
