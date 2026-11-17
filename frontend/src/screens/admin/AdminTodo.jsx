import { useRef } from 'react';
import { todo, todoRow } from '../../state/adminCopy.js';
import { todoHeadingCount } from '../../state/adminSummaryFormat.js';
import { useAdminContext } from '../../state/adminContext.js';
import { pickerTarget } from '../../state/adminSubtitles.js';
import { useAdminTodo } from '../../state/useAdminTodo.js';
import AdminStatusStrip from './AdminStatusStrip.jsx';
import AdminTodoGroup from './AdminTodoGroup.jsx';
import AdminTodoNotice from './AdminTodoNotice.jsx';
import SectionTitle from './SectionTitle.jsx';
import { useFallbackFocus } from './useFallbackFocus.js';

/**
 * Once a confirmed row is hidden, focus goes straight to the section title when it was on that row (or nowhere): the
 * button it was on is about to leave the page, and a fallback waiting for a later list change could be spent by
 * another.
 */
function leaveRow(opener) {
  const active = document.activeElement;
  const row = opener?.closest('li');
  if (!active || active === document.body || row?.contains(active)) {
    document.getElementById('admin-section-title')?.focus();
  }
}

const FILM_GROUPS = ['pickFilm', 'severalFiles'];

/**
 * To do: the status strip, then up to four groups of things only the owner can settle. Each source loads on its own
 * (a skeleton, then rows or an inline error with its own retry), so a failing list never holds back the others. Fix
 * match and Edit editions open from the rows, hosted by the layout.
 */
export default function AdminTodo() {
  const {
    settledAt,
    subtitlesVersion,
    changed,
    markChanged,
    openFixMatch,
    openEditEditions,
    openSubtitlePicker,
    armFocusFallback,
    adminSummary,
  } = useAdminContext();
  // The heading never waits for the summary, and a failed re-read leaves the last good count.
  const countText = todoHeadingCount(adminSummary.summary);
  const lists = useAdminTodo({
    settledAt,
    subtitlesVersion,
    changed,
    onChanged: markChanged,
  });
  const { attention, full, fullNeed, forced } = lists;
  // The rows on screen, so the fallback runs once a picked file's row has left (the opener goes with it).
  useFallbackFocus(
    lists.groups.map((g) => `${g.key}:${g.rows.map((r) => r.filmId).join(',')}`).join('|'),
  );

  // One save per film at a time: a click on a row that is saving, or one sent in the same frame, does nothing.
  const sent = useRef(new Set());
  const chooseFull = (row, choice) => {
    if (sent.current.has(row.filmId) || fullNeed.saving[row.filmId]) return;
    sent.current.add(row.filmId);
    // A saved choice settles the row: if focus falls to the page with it, it goes to the section title.
    armFocusFallback();
    fullNeed.choose(row.filmId, choice).finally(() => sent.current.delete(row.filmId));
  };

  const openEditions = (item, opener) =>
    openEditEditions({ id: item.filmId, title: item.title, year: item.year }, opener);
  const rowProps = {
    confirming: lists.confirming,
    problems: lists.problems,
    onConfirm: (item, opener) => lists.confirm(item, { onHidden: () => leaveRow(opener) }),
    onFindFilm: openFixMatch,
    onSortOut: openEditions,
    // A picked file settles the row: if focus falls to the page with it, it goes to the section title.
    fullSaving: fullNeed.saving,
    fullProblems: fullNeed.problems,
    onChooseFull: chooseFull,
    onAttachedFull: () => {
      armFocusFallback();
      markChanged();
    },
    onFindSubtitles: (row, kind, opener) =>
      openSubtitlePicker(pickerTarget(row, kind), opener, { onPicked: armFocusFallback }),
  };
  const groupFor = (key) => lists.groups.find((g) => g.key === key);
  const group = (key) => {
    const found = groupFor(key);
    return found ? <AdminTodoGroup key={key} group={found} {...rowProps} /> : null;
  };

  // A source's slot is its skeleton or, when its first read failed, its banner, until it has answered once. After
  // that a failed re-read keeps the last list on screen with no banner (as the summary does), and the next change
  // reads it again.
  const slot = (source, label, message, keys) => {
    if (source.data) return keys.map(group);
    if (source.error) {
      return (
        <AdminTodoNotice key={keys[0]} label={label} error={message} onRetry={source.reload} />
      );
    }
    return <AdminTodoNotice key={keys[0]} label={label} />;
  };

  return (
    <>
      <div className="admin__ttl">
        <SectionTitle>To do</SectionTitle>
        {countText ? <span className="admin__ttl-count">{countText}</span> : null}
      </div>
      <p className="admin__lead">{todo.intro}</p>
      <AdminStatusStrip
        summary={adminSummary.summary}
        error={adminSummary.error}
        onRetry={adminSummary.reload}
      />
      {slot(
        attention,
        'the films that need sorting out',
        todoRow.loadErrors.attention,
        FILM_GROUPS,
      )}
      {slot(full, 'the films missing English subtitles', todoRow.loadErrors.full, ['fullMissing'])}
      {slot(forced, 'the forced subtitles to find', todoRow.loadErrors.forced, ['forcedQuestion'])}
      {lists.allRead && lists.groups.length === 0 ? (
        <p className="admin__text">{todo.empty}</p>
      ) : null}
    </>
  );
}
