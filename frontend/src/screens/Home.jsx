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

/** How many Continue watching cards Home shows: one row, after the hero. The rest are spares for backfill. */
export const VISIBLE_CARDS = 4;
/** "See all" appears only when films are left out: more than the hero and the visible row. */
const SEE_ALL_OVER = VISIBLE_CARDS + 1;
const NONE = new Set();

/**
 * The one Continue watching row. A card that joined the visible cards after the first render (a backfill, or
 * an Undo restoring a film) is marked `entering` for the slide-in; the cards already there just shift.
 */
function ContinueRow({ films, total, onAct, linkRef, headingRef }) {
  const shown = films.slice(0, VISIBLE_CARDS);
  const before = useRef(null);
  const entering = before.current
    ? new Set(shown.filter((f) => !before.current.has(f.id)).map((f) => f.id))
    : NONE;
  useEffect(() => {
    before.current = new Set(shown.map((f) => f.id));
  });
  const seeAll = total > SEE_ALL_OVER;
  return (
    <FilmRow
      title="Continue watching"
      sub={`${total} in progress`}
      seeAllTo={seeAll ? '/library?show=in-progress&sort=last-watched' : undefined}
      seeAllLabel={`See all ${total} films in progress`}
      headingRef={headingRef}
      variant="stills"
    >
      {shown.map((f) => (
        <ContinueCard
          key={f.id}
          film={f}
          entering={entering.has(f.id)}
          onRemove={(film) => onAct('remove', film)}
          onMarkWatched={(film) => onAct('watched', film)}
          linkRef={linkRef(f.id)}
        />
      ))}
    </FilmRow>
  );
}

export default function Home() {
  const { data, error, loading, reload } = useHome();
  const cardLinks = useRef(new Map());
  const undoButton = useRef(null);
  const rowHeading = useRef(null);
  const pendingFocus = useRef(null);
  const cw = useContinueWatching(data, reload, (film) => {
    // A rollback can push the backfilled card, which has focus, out of the visible row: follow focus to the
    // reinstated film, but only if focus was in the row (anything else keeps its focus).
    const section = rowHeading.current?.closest('section');
    if (section?.contains(document.activeElement)) pendingFocus.current = film.id;
  });

  // After a removal focus moves to the card now in that slot, else the previous card, else the row heading
  // (or Undo if the row has gone); after Undo, to the restored card.
  useEffect(() => {
    const target = pendingFocus.current;
    if (target === null) return;
    pendingFocus.current = null;
    (target === 'undo'
      ? undoButton.current
      : (cardLinks.current.get(target) ?? rowHeading.current)
    )?.focus();
  });

  if (loading && !data) return <HomeSkeleton />;
  if (error && !data) return <ErrorState onRetry={reload} />;
  if (!data) return null;

  const { hero, row: continueWatching } = cw;
  const { recentlyAdded = [], notWatchedInYears = [] } = data;

  const act = (kind, film) => {
    const at = continueWatching.findIndex((f) => f.id === film.id);
    // The next film in the list is the one that moves into this slot (the backfilled card, at the end).
    const next = continueWatching[at + 1] ?? continueWatching[at - 1];
    pendingFocus.current = next ? next.id : 'undo';
    (kind === 'remove' ? cw.remove : cw.markWatched)(film);
  };
  const undo = () => {
    pendingFocus.current = cw.action.film.id;
    cw.undo();
  };
  const registerCard = (id) => (el) => {
    if (el) cardLinks.current.set(id, el);
    else cardLinks.current.delete(id);
  };
  // A film marked watched here shows as watched, and no longer in progress, in every other row too, until the page
  // data is reloaded. Only the film acted on is changed: a rewatch elsewhere keeps its progress.
  const seen = (f) =>
    f.id in cw.watched
      ? { ...f, watched: cw.watched[f.id], ...(cw.watched[f.id] ? { inProgress: false } : {}) }
      : f;
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
        <ContinueRow
          films={continueWatching}
          total={cw.total}
          onAct={act}
          linkRef={registerCard}
          headingRef={rowHeading}
        />
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
