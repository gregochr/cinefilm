import { useNavigate, useParams } from 'react-router-dom';
import { captionTitle, labelTitle } from '../state/titles.js';
import { imageUrl } from '../api/images.js';
import { Poster } from '../components/Poster.jsx';
import { GridSkeleton } from '../components/Skeleton.jsx';
import { ErrorState } from '../components/StateMessages.jsx';
import { useCollection } from '../state/usePeopleAndCollections.js';

function MissingTile({ part }) {
  const url = imageUrl(part.posterPath);
  return (
    <div
      className="missing"
      role="group"
      aria-label={`${labelTitle(part.title, part.year)}, not in your library`}
    >
      <div className="missing__art">
        {url ? <img src={url} alt="" loading="lazy" className="missing__img" /> : null}
        <span className="missing__label">Not in your library</span>
      </div>
      <span className="poster__caption">{captionTitle(part.title, part.year)}</span>
    </div>
  );
}

export default function Collection() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { data: collection, error, loading, reload } = useCollection(id);

  if (loading && !collection) return <GridSkeleton count={4} />;
  if (error && !collection) return <ErrorState onRetry={reload} />;
  if (!collection) return null;

  const owned = collection.parts.filter((p) => p.owned).length;
  const total = collection.parts.length;

  return (
    <div className="page__stack">
      <button type="button" className="backlink" onClick={() => navigate(-1)}>
        &larr; Back
      </button>
      <div className="library__heading">
        <h1 className="page-title">{collection.name}</h1>
        <span className="library__count">
          {owned} of {total} films in your library
        </span>
      </div>
      <div className="poster-grid poster-grid--wide">
        {collection.parts.map((part, i) =>
          part.owned ? (
            <Poster key={part.film.id} film={part.film} />
          ) : (
            <MissingTile key={`missing-${i}`} part={part} />
          ),
        )}
      </div>
    </div>
  );
}
