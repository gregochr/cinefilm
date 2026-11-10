import { useEffect, useRef } from 'react';
import Art from '../components/Art.jsx';
import { ButtonLink } from '../components/Button.jsx';
import FilmRow from '../components/FilmRow.jsx';
import { PlayIcon } from '../components/icons.jsx';
import { ContinueCard } from '../components/ContinueCard.jsx';
import { Poster } from '../components/Poster.jsx';
import { HomeSkeleton } from '../components/Skeleton.jsx';
import { EmptyLibrary, ErrorState } from '../components/StateMessages.jsx';
import UndoToast from '../components/UndoToast.jsx';
import { formatLeft, formatResume, fraction } from '../player/time.js';
import { useContinueWatching } from '../state/useContinueWatching.js';
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
  const cw = useContinueWatching(data, reload);
  const cardLinks = useRef(new Map());
  const undoButton = useRef(null);
  const pendingFocus = useRef(null);

  // After a removal focus moves to a neighbouring card (or Undo); after Undo, to the restored card.
  useEffect(() => {
    const target = pendingFocus.current;
    if (target === null) return;
    pendingFocus.current = null;
    (target === 'undo' ? undoButton.current : cardLinks.current.get(target))?.focus();
  });

  if (loading && !data) return <HomeSkeleton />;
  if (error && !data) return <ErrorState onRetry={reload} />;
  if (!data) return null;

  const { hero, row: continueWatching } = cw;
  const { recentlyAdded = [], notWatchedInYears = [] } = data;

  const act = (run, film) => {
    const at = continueWatching.findIndex((f) => f.id === film.id);
    const next = continueWatching[at + 1] ?? continueWatching[at - 1];
    pendingFocus.current = next ? next.id : 'undo';
    run(film);
  };
  const undo = () => {
    pendingFocus.current = cw.action.film.id;
    cw.undo();
  };
  const registerCard = (id) => (el) => {
    if (el) cardLinks.current.set(id, el);
    else cardLinks.current.delete(id);
  };
  // A film marked watched here shows as watched in every other row too, until the page data is reloaded.
  const seen = (f) => (f.id in cw.watched ? { ...f, watched: cw.watched[f.id] } : f);
  const toast = <UndoToast action={cw.action} undoRef={undoButton} onUndo={undo} />;
  const theme = data.weeklyTheme;
  const empty =
    !hero &&
    !continueWatching.length &&
    !recentlyAdded.length &&
    !notWatchedInYears.length &&
    !theme?.films?.length;
  if (empty) {
    return (
      <>
        <EmptyLibrary />
        {toast}
      </>
    );
  }

  return (
    <div className="page__stack">
      {hero ? <Hero film={seen(hero)} /> : null}
      {continueWatching.length ? (
        <FilmRow
          title="Continue watching"
          sub={`${continueWatching.length} in progress`}
          variant="stills"
        >
          {continueWatching.map((f) => (
            <ContinueCard
              key={f.id}
              film={f}
              onRemove={(film) => act(cw.remove, film)}
              onMarkWatched={(film) => act(cw.markWatched, film)}
              linkRef={registerCard(f.id)}
            />
          ))}
        </FilmRow>
      ) : null}
      {recentlyAdded.length ? (
        <FilmRow title="Recently added" sub="Last 30 days" seeAllTo="/library?sort=added">
          {recentlyAdded.map((f) => (
            <Poster key={f.id} film={seen(f)} />
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
            <Poster key={f.id} film={seen(f)} />
          ))}
        </FilmRow>
      ) : null}
      {theme?.films?.length ? (
        <FilmRow title={theme.title} sub={theme.caption} seeAllTo="/library">
          {theme.films.map((f) => (
            <Poster key={f.id} film={seen(f)} />
          ))}
        </FilmRow>
      ) : null}
      {toast}
    </div>
  );
}
