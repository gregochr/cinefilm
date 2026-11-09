import { useState } from 'react';
import { joinMeta } from '../state/titles.js';
import { Link, useParams } from 'react-router-dom';
import { BACKDROP_SIZE, POSTER_SIZE_LARGE } from '../api/images.js';
import Art from '../components/Art.jsx';
import { Button, ButtonLink } from '../components/Button.jsx';
import { Picker, Switch } from '../components/Controls.jsx';
import { CheckIcon, PlayIcon, RestartIcon } from '../components/icons.jsx';
import { DetailSkeleton } from '../components/Skeleton.jsx';
import { ErrorState } from '../components/StateMessages.jsx';
import { formatLeft, formatResume, formatRuntime, fraction } from '../player/time.js';
import {
  SUBTITLE_OFF,
  audioText,
  qualityName,
  resolveAudioChoice,
  resolveSubtitleChoice,
  streamKey,
  subtitleOptions,
} from '../player/tracks.js';
import { displayName } from '../state/collections.js';
import { useFilm, useFilmActions } from '../state/useFilms.js';

function addedLabel(iso) {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return `added ${date.toLocaleString('en-GB', { month: 'long', year: 'numeric' })}`;
}

function qualityOptions(edition) {
  return edition.qualities.map((q) => ({
    value: q.id,
    label:
      q.id === 'auto'
        ? 'Auto — Original at home'
        : `${qualityName(q.id)}${q.premade ? ' · pre-made' : ''}`,
    disabled: !q.available,
  }));
}

function EditionPicker({ editions, value, onChange }) {
  return (
    <fieldset className="editions">
      <legend className="field__label">Edition &middot; {editions.length} in your library</legend>
      <div className="editions__grid">
        {editions.map((e) => (
          <label key={e.id} className={`edition${e.id === value ? ' edition--on' : ''}`}>
            <span className="edition__top">
              <input
                type="radio"
                name="edition"
                checked={e.id === value}
                onChange={() => onChange(e.id)}
              />
              <span className="edition__name">{e.name}</span>
            </span>
            <span className="edition__meta">{joinMeta(e.year, formatRuntime(e.runtimeMs))}</span>
            {e.note ? <span className="edition__note">{e.note}</span> : null}
          </label>
        ))}
      </div>
    </fieldset>
  );
}

export default function FilmDetail() {
  const { id } = useParams();
  const { data: film, error, loading, reload } = useFilm(id);
  const actions = useFilmActions(id);
  const [pickedEdition, setPickedEdition] = useState(null);
  const [dim, setDim] = useState(true);
  const [quality, setQuality] = useState(null);
  const [audio, setAudio] = useState(null);
  const [subtitle, setSubtitle] = useState(null);

  if (loading && !film) return <DetailSkeleton />;
  if (error && !film) return <ErrorState onRetry={reload} />;
  if (!film) return null;

  const prefs = film.preferences || {};
  const multi = film.editions.length > 1;
  const edition =
    film.editions.find((e) => e.id === pickedEdition) ||
    film.editions.find((e) => e.id === prefs.editionId) ||
    film.editions[0];
  const override = actions.overrides[edition.id] || {};
  const watched = override.watched ?? edition.watched;
  const positionMs = override.positionMs ?? edition.positionMs;
  const resuming = positionMs > 0 && !watched;

  const qualityValue = quality ?? prefs.quality ?? 'auto';
  const audioValue = (() => {
    const wanted = audio ?? resolveAudioChoice(film.editions, edition, prefs.audioStreamId);
    if (edition.audioStreams.some((s) => String(streamKey(s)) === String(wanted)))
      return String(wanted);
    const fallback = edition.audioStreams.find((s) => s.isDefault) || edition.audioStreams[0];
    return fallback ? String(streamKey(fallback)) : '';
  })();
  const subtitleChoices = subtitleOptions(edition.subtitleStreams);
  const hasForced = edition.subtitleStreams.some((s) => s.forced);
  const subtitleValue = String(
    subtitle ?? resolveSubtitleChoice(film.editions, edition, prefs.subtitle),
  );

  const pickEdition = (editionId) => {
    setPickedEdition(editionId);
    actions.savePreferences({ editionId });
  };

  const playBase = `/films/${film.id}/play?edition=${edition.id}${dim ? '' : '&dim=0'}`;
  const playLabel = resuming
    ? `Resume${multi ? ` ${edition.name}` : ''} from ${formatResume(positionMs)}`
    : `Play${multi ? ` ${edition.name}` : ''}`;

  const meta = [
    film.year ? <span key="y">{film.year}</span> : null,
    <span key="r">{formatRuntime(edition.runtimeMs)}</span>,
    film.genres?.length ? <span key="g">{film.genres.join(', ')}</span> : null,
    film.collection ? (
      <Link key="c" to={`/collections/${film.collection.id}`} className="detail__collection">
        {displayName(film.collection.name)} collection
      </Link>
    ) : null,
  ].filter(Boolean);

  const file = edition.file || {};
  const fileLine = [
    'Original file',
    [file.height ? `${file.height}p` : null, file.videoCodec].filter(Boolean).join(' '),
    file.audioSummary,
    addedLabel(file.addedAt),
  ]
    .filter(Boolean)
    .join(' · ');

  return (
    <div className="detail">
      <Art
        kind="backdrop"
        path={film.backdropPath}
        id={film.id}
        title={film.title}
        size={BACKDROP_SIZE}
        showTitle={false}
        className="detail__backdrop"
      />
      <div className="detail__scrim" aria-hidden="true" />
      <div className="detail__body">
        <Art
          kind="poster"
          path={film.posterPath}
          id={film.id}
          title={film.title}
          size={POSTER_SIZE_LARGE}
          className="detail__poster"
        />
        <div className="detail__main">
          <h1 className="detail__title">{film.title}</h1>
          <div className="detail__meta">
            {meta.map((m, i) => (
              <span key={m.key} className="detail__metaitem">
                {i > 0 ? <span aria-hidden="true">&middot;</span> : null}
                {m}
              </span>
            ))}
          </div>
          {film.overview ? <p className="detail__plot">{film.overview}</p> : null}

          {multi ? (
            <EditionPicker editions={film.editions} value={edition.id} onChange={pickEdition} />
          ) : null}

          <div className="detail__actions">
            <ButtonLink variant="primary" size="xl" to={playBase}>
              <PlayIcon size={16} />
              {playLabel}
            </ButtonLink>
            {resuming ? (
              <ButtonLink size="xl" to={`${playBase}&start=0`}>
                <RestartIcon size={16} />
                Start from beginning
              </ButtonLink>
            ) : null}
            <Button
              variant="icon"
              size="xl"
              aria-label={watched ? 'Mark as unwatched' : 'Mark as watched'}
              aria-pressed={watched}
              onClick={() => actions.setWatched(edition.id, !watched)}
            >
              <CheckIcon size={18} />
            </Button>
            <Switch label="Dim the lights" checked={dim} onChange={setDim} />
          </div>
          {resuming ? (
            <div className="detail__progress">
              <div className="detail__track" aria-hidden="true">
                <div
                  className="detail__fill"
                  style={{ width: `${fraction(positionMs, edition.runtimeMs) * 100}%` }}
                />
              </div>
              <span className="detail__left">{formatLeft(positionMs, edition.runtimeMs)}</span>
            </div>
          ) : null}

          <div className="settings">
            <Picker
              label="Quality"
              value={qualityValue}
              options={qualityOptions(edition)}
              onChange={(v) => {
                setQuality(v);
                actions.savePreferences({ quality: v });
              }}
            />
            {edition.subtitleStreams.length === 0 ? (
              <Picker label="Subtitles" inertText="None for this film">
                <Link to="/admin" className="settings__link">
                  Find subtitles in Admin
                </Link>
              </Picker>
            ) : (
              <Picker
                label="Subtitles"
                value={subtitleValue}
                options={subtitleChoices.map((o) => ({
                  value: String(o.value),
                  label:
                    o.value === SUBTITLE_OFF && hasForced ? 'Off · forced when needed' : o.label,
                }))}
                onChange={(v) => {
                  setSubtitle(v);
                  actions.savePreferences({ subtitle: v === SUBTITLE_OFF ? v : Number(v) });
                }}
              />
            )}
            {edition.audioStreams.length > 1 ? (
              <Picker
                label="Audio"
                value={audioValue}
                options={edition.audioStreams.map((s) => ({
                  value: String(streamKey(s)),
                  label: audioText(s),
                }))}
                onChange={(v) => {
                  setAudio(v);
                  actions.savePreferences({ audioStreamId: Number(v) });
                }}
              />
            ) : (
              <div className="field">
                <span className="field__label">Audio</span>
                <span className="field__value">
                  {edition.audioStreams[0] ? audioText(edition.audioStreams[0]) : '—'}
                </span>
              </div>
            )}
          </div>

          <div className="credits">
            <div className="credits__col">
              <h2 className="credits__label">Directed by</h2>
              <div className="credits__directors">
                {film.directors.map((d) => (
                  <Link key={d.id} to={`/people/${d.id}`} className="credits__director">
                    {d.name}
                  </Link>
                ))}
              </div>
            </div>
            <div className="credits__col">
              <h2 className="credits__label">Starring</h2>
              <div className="credits__cast">
                {film.cast.map((c) => (
                  <Link
                    key={c.person.id}
                    to={`/people/${c.person.id}`}
                    title={c.character || undefined}
                  >
                    {c.person.name}
                  </Link>
                ))}
              </div>
            </div>
          </div>
          <p className="detail__file">{fileLine}</p>
        </div>
      </div>
    </div>
  );
}
