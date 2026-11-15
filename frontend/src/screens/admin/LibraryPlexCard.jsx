import { useId, useState } from 'react';
import { ActionWithHint } from '../../components/ActionWithHint.jsx';
import { Button } from '../../components/Button.jsx';
import { buttons, libraryPage } from '../../state/adminCopy.js';
import {
  broughtAcross,
  moreText,
  plexHeaderLine,
  plexProgressText,
  plexServerState,
  reportLists,
  reportTiles,
} from '../../state/plexFormat.js';
import { progressPercent } from '../../state/scanFormat.js';

/** The count tiles and named lists of an import report. */
function ImportReport({ report, id }) {
  return (
    <div id={id} role="group" aria-label="Last import report">
      <ul className="admin__counts" aria-label="Import report">
        {reportTiles(report).map((tile) => (
          <li key={tile.key} className="admin__count">
            <span className="admin__count-label">{tile.label}</span>
            <span className="admin__count-value">{tile.value}</span>
          </li>
        ))}
      </ul>
      {reportLists(report).map((list) => (
        <div key={list.key} className="admin__report-list">
          <h3 className="admin__subtitle">{list.title}</h3>
          <ul className="admin__candidates" aria-label={list.title}>
            {list.items.map((item, i) => (
              <li key={`${i}-${item}`}>{item}</li>
            ))}
          </ul>
          {moreText(list) ? <p className="admin__text">{moreText(list)}</p> : null}
        </div>
      ))}
    </div>
  );
}

/**
 * The "Import from Plex" card, drawn only when Plex is configured on the server (nothing renders until the status
 * says so). A key-value block says whether Plex answers and what the last import brought across; "Import from Plex
 * again" copies again, "Try connecting to Plex" shows only while Plex is unreachable, and the last report is behind a
 * quiet "Last report" button that opens and closes it.
 */
export default function LibraryPlexCard({ plex, starting, notice, onImport, onRecheck }) {
  const [reportOpen, setReportOpen] = useState(false);
  const reportId = useId();
  if (!plex || !plex.configured) return null;
  const running = plex.running;
  const last = plex.lastImport;
  return (
    <section id="plex-import" className="admin__card" aria-labelledby="admin-plex-title">
      <div className="admin__head">
        <h2 id="admin-plex-title" className="admin__title">
          {libraryPage.plex.title}
        </h2>
        <span className="admin__mono admin__why">{plexHeaderLine(last)}</span>
      </div>
      {running && plex.progress ? (
        <div className="admin__progress">
          <div
            className="admin__bar"
            role="progressbar"
            aria-label="Import progress"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={progressPercent(plex.progress)}
            aria-valuetext={plexProgressText(plex.progress)}
          >
            <div className="admin__fill" style={{ width: `${progressPercent(plex.progress)}%` }} />
          </div>
          <div className="admin__progress-line">
            <span className="admin__mono">{plexProgressText(plex.progress)}</span>
            {plex.progress.current ? (
              <span className="admin__mono admin__now">Now: {plex.progress.current}</span>
            ) : null}
          </div>
        </div>
      ) : null}
      <dl className="admin__rows admin__rows--wide">
        <dt>{libraryPage.plex.server}</dt>
        <dd>{plexServerState(plex)}</dd>
        <dt>{libraryPage.plex.broughtAcross}</dt>
        <dd>{broughtAcross(last)}</dd>
      </dl>
      <div className="admin__actions admin__actions--wrap">
        <ActionWithHint
          layout="line"
          hint={buttons.importPlex.hint}
          disabled={running || starting || !plex.reachable}
          onClick={onImport}
        >
          {running ? buttons.importPlex.busyLabel : buttons.importPlex.label}
        </ActionWithHint>
        {!running && !plex.reachable ? (
          <ActionWithHint layout="line" hint={buttons.tryPlex.hint} onClick={onRecheck}>
            {buttons.tryPlex.label}
          </ActionWithHint>
        ) : null}
        {last?.report ? (
          <Button
            className="btn--bare admin__quiet"
            aria-expanded={reportOpen}
            aria-controls={reportOpen ? reportId : undefined}
            onClick={() => setReportOpen((open) => !open)}
          >
            {libraryPage.plex.lastReport}
          </Button>
        ) : null}
        {notice ? (
          <span className="admin__notice" role="status">
            {notice}
          </span>
        ) : null}
      </div>
      {reportOpen && last?.report ? <ImportReport report={last.report} id={reportId} /> : null}
    </section>
  );
}
