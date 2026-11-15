import { useId, useState } from 'react';
import { Button } from '../../components/Button.jsx';
import SubtitlesPanel from '../../components/SubtitlesPanel.jsx';
import { subtitlesPage } from '../../state/adminCopy.js';
import { filmTitle } from '../../state/adminSubtitles.js';
import { fullSubtitleLine } from '../../state/fullNeed.js';
import SubtitleSearchLine from './SubtitleSearchLine.jsx';

const copy = subtitlesPage.else;

/** Copy with `bold` parts drawn as <b> (AR2's lead and note). */
export function Rich({ parts }) {
  return parts.map((part) =>
    part.bold ? <b key={part.text}>{part.text}</b> : <span key={part.text}>{part.text}</span>,
  );
}

/** One figure and what it counts. */
function Tile({ value, label }) {
  return (
    <li className="admin__count">
      <span className="admin__count-value">{value.toLocaleString('en-GB')}</span>
      <span className="admin__count-label">{label}</span>
    </li>
  );
}

/**
 * "Everything else": the figures of what is not on the tabs (each shown once it is known), "Show the {n} films" (a
 * read-only list), the quiet "Show all subtitle files" (the file list with every per-file tool, until they move to
 * the film page, 8b), the OpenSubtitles search status and the note pointing to the film page and the player.
 * `figures` is `everythingElse(...)`; `onChanged` runs when a file tool changed something the lists depend on.
 */
export default function SubtitleEverythingElse({ figures, scan, version, onChanged }) {
  const [films, setFilms] = useState(false);
  const [files, setFiles] = useState(false);
  const filmsId = useId();
  const filesId = useId();
  const { haveSubtitles, inEnglish, beingChecked } = figures;
  return (
    <>
      <section className="admin__todo-group" aria-labelledby="admin-subtitles-else">
        <div className="admin__todo-head">
          <h2 id="admin-subtitles-else" className="admin__todo-heading">
            {copy.heading}
          </h2>
        </div>
        <div className="admin__else-body">
          <ul className="admin__counts" aria-label={copy.heading}>
            {haveSubtitles != null ? (
              <Tile value={haveSubtitles} label={copy.haveSubtitles(haveSubtitles)} />
            ) : null}
            {inEnglish != null ? (
              <Tile value={inEnglish} label={copy.inEnglish(inEnglish)} />
            ) : null}
            {beingChecked != null ? (
              <Tile value={beingChecked} label={copy.beingCheckedLabel} />
            ) : null}
          </ul>
          <SubtitleSearchLine scan={scan} />
          <div className="admin__actions admin__else-actions">
            {haveSubtitles != null && haveSubtitles > 0 ? (
              <Button
                aria-expanded={films}
                aria-controls={films ? filmsId : undefined}
                onClick={() => setFilms(!films)}
              >
                {films ? copy.hideFilms(haveSubtitles) : copy.showFilms(haveSubtitles)}
              </Button>
            ) : null}
            <Button
              className="admin__todo-quiet"
              aria-expanded={files}
              aria-controls={files ? filesId : undefined}
              onClick={() => setFiles(!files)}
            >
              {files ? copy.hideFiles : copy.showFiles}
            </Button>
          </div>
          {films && figures.films ? (
            <ul id={filmsId} className="admin__else-films" aria-label={copy.filmsList}>
              {figures.films.map((row) => (
                <li key={row.filmId} className="admin__else-film">
                  {filmTitle(row)}
                  <span className="admin__todo-year"> · {fullSubtitleLine(row)}</span>
                </li>
              ))}
            </ul>
          ) : null}
        </div>
        <p className="admin__else-note">
          <Rich parts={copy.note} />
        </p>
      </section>
      <div id={filesId}>
        {files ? <SubtitlesPanel version={version} onChanged={onChanged} /> : null}
      </div>
    </>
  );
}
