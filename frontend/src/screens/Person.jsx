import { useNavigate, useParams } from 'react-router-dom';
import { PROFILE_SIZE, imageUrl } from '../api/images.js';
import { initials } from '../components/placeholder.js';
import { Poster } from '../components/Poster.jsx';
import { GridSkeleton } from '../components/Skeleton.jsx';
import { ErrorState } from '../components/StateMessages.jsx';
import { usePerson } from '../state/usePeopleAndCollections.js';

function describe(person) {
  const roles = person.films.flatMap((f) => f.roles);
  const directs = roles.some((r) => /director/i.test(r));
  const acts = roles.some((r) => !/director/i.test(r));
  const kind = directs && acts ? 'Actor and director' : directs ? 'Director' : 'Actor';
  const n = person.films.length;
  return `${kind} · ${n} ${n === 1 ? 'film' : 'films'} in your library`;
}

export default function Person() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { data: person, error, loading, reload } = usePerson(id);

  if (loading && !person) return <GridSkeleton count={4} />;
  if (error && !person) return <ErrorState onRetry={reload} />;
  if (!person) return null;
  const photo = imageUrl(person.profilePath, PROFILE_SIZE);

  return (
    <div className="page__stack">
      <button type="button" className="backlink" onClick={() => navigate(-1)}>
        &larr; Back
      </button>
      <div className="person__head">
        {photo ? (
          <img
            className="person__photo"
            src={photo}
            alt=""
            loading="lazy"
            width="160"
            height="160"
          />
        ) : (
          <div className="person__photo person__photo--placeholder" aria-hidden="true">
            {initials(person.name)}
          </div>
        )}
        <div className="person__text">
          <h1 className="person__name">{person.name}</h1>
          <div className="person__sub">{describe(person)}</div>
        </div>
      </div>
      <section className="row" aria-labelledby="person-films">
        <h2 id="person-films" className="row__title">
          In your library
        </h2>
        <div className="poster-grid poster-grid--wide">
          {person.films.map(({ film, roles }) => (
            <Poster key={film.id} film={film} caption={roles.join(', ')} />
          ))}
        </div>
      </section>
    </div>
  );
}
