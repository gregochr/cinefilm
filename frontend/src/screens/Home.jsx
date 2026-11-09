import Art from '../components/Art.jsx';
import { ButtonLink } from '../components/Button.jsx';
import FilmRow from '../components/FilmRow.jsx';
import { PlayIcon } from '../components/icons.jsx';
import { ContinueCard, Poster } from '../components/Poster.jsx';
import { HomeSkeleton } from '../components/Skeleton.jsx';
import { EmptyLibrary, ErrorState } from '../components/StateMessages.jsx';
import { formatLeft, formatResume, fraction } from '../player/time.js';
import { useHome } from '../state/useHome.js';

function Hero({ film }) {
  const progress = film.progress;
  const playTo = `/films/${film.id}/play${progress?.editionId ? `?edition=${progress.editionId}` : ''}`;
  const meta = [
    film.year,
    film.director,
    progress ? formatLeft(progress.positionMs, progress.runtimeMs) : null,
  ]
    .filter(Boolean)
    .join(' · ');
  return (
    <section className="hero" aria-label={`Resume ${film.title}`}>
      <Art
        kind="backdrop"
        path={film.backdropPath}
        id={film.id}
        title={film.title}
        className="hero__art"
        showTitle={false}
      />
      <div className="hero__scrim" aria-hidden="true" />
      <div className="hero__body">
        <div className="eyebrow">Pick up where you left off</div>
        <h1 className="hero__title">{film.title}</h1>
        <div className="hero__meta">{meta}</div>
        {progress ? (
          <div className="hero__progress" aria-hidden="true">
            <div
              className="hero__fill"
              style={{ width: `${fraction(progress.positionMs, progress.runtimeMs) * 100}%` }}
            />
          </div>
        ) : null}
        <div className="hero__actions">
          <ButtonLink variant="primary" size="lg" to={playTo}>
            <PlayIcon size={16} />
            {progress ? `Resume from ${formatResume(progress.positionMs)}` : 'Play'}
          </ButtonLink>
          <ButtonLink size="lg" to={`/films/${film.id}`}>
            Details
          </ButtonLink>
        </div>
      </div>
    </section>
  );
}

export default function Home() {
  const { data, error, loading, reload } = useHome();
  if (loading && !data) return <HomeSkeleton />;
  if (error && !data) return <ErrorState onRetry={reload} />;
  if (!data) return null;

  const { hero, continueWatching = [], recentlyAdded = [], notWatchedInYears = [] } = data;
  const theme = data.weeklyTheme;
  const empty =
    !hero &&
    !continueWatching.length &&
    !recentlyAdded.length &&
    !notWatchedInYears.length &&
    !theme?.films?.length;
  if (empty) return <EmptyLibrary />;

  return (
    <div className="page__stack">
      {hero ? <Hero film={hero} /> : null}
      {continueWatching.length ? (
        <FilmRow
          title="Continue watching"
          sub={`${continueWatching.length} in progress`}
          variant="stills"
        >
          {continueWatching.map((f) => (
            <ContinueCard key={f.id} film={f} />
          ))}
        </FilmRow>
      ) : null}
      {recentlyAdded.length ? (
        <FilmRow title="Recently added" sub="Last 30 days" seeAllTo="/library?sort=added">
          {recentlyAdded.map((f) => (
            <Poster key={f.id} film={f} />
          ))}
        </FilmRow>
      ) : null}
      {notWatchedInYears.length ? (
        <FilmRow
          title="Not watched in years"
          sub="Five years or more"
          seeAllTo="/library?show=watched&sort=title"
        >
          {notWatchedInYears.map((f) => (
            <Poster key={f.id} film={f} />
          ))}
        </FilmRow>
      ) : null}
      {theme?.films?.length ? (
        <FilmRow title={theme.title} sub={theme.caption} seeAllTo="/library">
          {theme.films.map((f) => (
            <Poster key={f.id} film={f} />
          ))}
        </FilmRow>
      ) : null}
    </div>
  );
}
