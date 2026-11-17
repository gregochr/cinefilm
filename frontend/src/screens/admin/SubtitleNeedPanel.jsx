import { useEffect, useRef } from 'react';
import { subtitlesPage } from '../../state/adminCopy.js';
import { pickerTarget } from '../../state/adminSubtitles.js';
import { panelId, tabId } from './AdminTabList.jsx';
import AdminTodoNotice from './AdminTodoNotice.jsx';
import SubtitleNeedRow from './SubtitleNeedRow.jsx';

const LABELS = { foreign: 'the foreign-language films', forced: 'the forced subtitles to find' };

/**
 * One tab's panel: the films the server flagged `unresolved` for a kind (`foreign` or `forced`), each a row, under a
 * heading with their count. `need` is the list's state (`useNeedChoices`) and `rows` its unresolved rows (null until
 * read). A list that has not answered is a static skeleton, or an inline banner with its own retry when its first read
 * failed; a re-read that fails keeps the rows and says so above them. `active` shows the panel (the other is hidden,
 * still in the page so the tab's `aria-controls` is true). `onChanged` runs after a file is attached; `onFind(target, opener, { onPicked })`
 * opens the OpenSubtitles picker for a row (`target` is what `pickerTarget` builds; `onPicked`
 * runs when a file was downloaded for it).
 *
 * A choice is one request per film: a click on a row that is saving does nothing. When a saved choice or an attached
 * file makes the row leave and focus fell to the page with it, focus moves to this panel (the tab's content), where the
 * next rows are; focus that is somewhere real is left alone, and a failed save keeps the row and its focus.
 */
export default function SubtitleNeedPanel({ kind, active, need, rows, onChanged, onFind }) {
  const panelRef = useRef(null);
  const sent = useRef(new Set());
  // The films whose row is expected to leave (a saved choice, an attached file); a Set, as two rows can be in flight.
  const leaving = useRef(new Set());

  useEffect(() => {
    if (rows === null || leaving.current.size === 0) return;
    let gone = false;
    for (const id of [...leaving.current]) {
      if (!rows.some((row) => row.filmId === id)) {
        leaving.current.delete(id);
        gone = true;
      } else if (!need.saving[id]) {
        leaving.current.delete(id);
      }
    }
    if (!gone) return;
    const active = document.activeElement;
    if (!active || active === document.body || !active.isConnected) panelRef.current?.focus();
  }, [rows, need.saving]);

  const choose = (row, choice) => {
    if (sent.current.has(row.filmId) || need.saving[row.filmId]) return;
    sent.current.add(row.filmId);
    leaving.current.add(row.filmId);
    need.choose(row.filmId, choice).finally(() => sent.current.delete(row.filmId));
  };
  // The picked file settles the row, so the focus rule above covers it too, once the download succeeded: the marker is
  // set then (not when the picker opens, a re-read while it is open would clear it), also if the picker was closed.
  const find = (row, opener) => {
    onFind(pickerTarget(row, kind === 'foreign' ? 'full' : 'forced'), opener, {
      onPicked: () => {
        leaving.current.add(row.filmId);
      },
    });
  };
  const attached = (row) => {
    leaving.current.add(row.filmId);
    onChanged();
  };

  let body;
  if (rows === null) {
    body = (
      <AdminTodoNotice
        label={LABELS[kind]}
        error={need.error ? subtitlesPage.loadErrors[kind] : undefined}
        onRetry={need.reload}
      />
    );
  } else {
    body = (
      <>
        {need.error ? (
          <AdminTodoNotice
            label={LABELS[kind]}
            error={subtitlesPage.refreshErrors[kind]}
            onRetry={need.reload}
          />
        ) : null}
        <section className="admin__todo-group" aria-labelledby={`admin-subtitles-heading-${kind}`}>
          <div className="admin__todo-head">
            <h2 id={`admin-subtitles-heading-${kind}`} className="admin__todo-heading">
              {subtitlesPage.panels[kind].heading}
            </h2>
            <p className="admin__todo-why">{subtitlesPage.filmsCount(rows.length)}</p>
          </div>
          {rows.length === 0 ? (
            <p className="admin__text admin__subs-empty">{subtitlesPage.empty[kind]}</p>
          ) : (
            <ul className="admin__todo-rows" aria-label={subtitlesPage.panels[kind].heading}>
              {rows.map((row) => (
                <SubtitleNeedRow
                  key={row.filmId}
                  row={row}
                  kind={kind}
                  saving={Boolean(need.saving[row.filmId])}
                  problem={need.problems[row.filmId]}
                  onChoose={choose}
                  onAttached={attached}
                  onFind={find}
                />
              ))}
            </ul>
          )}
        </section>
      </>
    );
  }

  return (
    <div
      ref={panelRef}
      role="tabpanel"
      id={panelId(kind)}
      aria-labelledby={tabId(kind)}
      tabIndex={-1}
      hidden={!active}
      className="admin__subs-panel"
    >
      {body}
    </div>
  );
}
