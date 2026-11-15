import { ActionWithHint } from '../../components/ActionWithHint.jsx';
import { buttons, todo } from '../../state/adminCopy.js';
import { todoHeadingCount } from '../../state/adminSummaryFormat.js';
import AdminStatusStrip from './AdminStatusStrip.jsx';
import { SkeletonBlock } from '../../components/Skeleton.jsx';
import { ErrorState } from '../../components/StateMessages.jsx';
import { formatRuntime } from '../../player/time.js';
import { useFallbackFocus } from './useFallbackFocus.js';
import SectionTitle from './SectionTitle.jsx';
import { useAdminContext } from '../../state/adminContext.js';
import { useAttention } from '../../state/useAdmin.js';
import { kindLabel, titleWithYear } from '../../state/scanFormat.js';

function Candidates({ candidates }) {
  return (
    <ul className="admin__candidates" aria-label="Possible matches">
      {candidates.map((c) => (
        <li key={c.tmdbId}>
          {titleWithYear(c.title, c.year)}
          {c.runtimeMinutes ? (
            <span className="admin__mono"> · {formatRuntime(c.runtimeMinutes * 60000)}</span>
          ) : null}
        </li>
      ))}
    </ul>
  );
}

function AttentionPanel({ attention, loading, error, onRetry, onFixMatch, onEditEditions }) {
  let body;
  if (loading && !attention) {
    body = (
      <div role="status" aria-label="Loading">
        <SkeletonBlock style={{ height: 120 }} />
      </div>
    );
  } else if (error && !attention) {
    body = (
      <ErrorState onRetry={onRetry}>Couldn&rsquo;t load the films needing attention.</ErrorState>
    );
  } else if (attention && attention.items.length === 0) {
    body = <p className="admin__text">Nothing needs attention.</p>;
  } else if (attention) {
    body = (
      <ul className="admin__list">
        {attention.items.map((item) => (
          <li key={`${item.kind}-${item.filmId}-${item.files.join('|')}`} className="admin__item">
            <div className="admin__item-head">
              <span className="admin__item-title">{titleWithYear(item.title, item.year)}</span>
              <span className="admin__tag">{kindLabel(item.kind)}</span>
            </div>
            <div className="admin__item-detail">{item.detail}</div>
            <div className="admin__item-files admin__mono">{item.files.join(' · ')}</div>
            {item.candidates.length > 0 ? <Candidates candidates={item.candidates} /> : null}
            {item.kind === 'needs-match' ? (
              <div className="admin__actions admin__item-actions">
                <ActionWithHint
                  hint={buttons.findFilm.hint}
                  onClick={(event) => onFixMatch(item, event.currentTarget)}
                >
                  {buttons.findFilm.label}
                </ActionWithHint>
              </div>
            ) : null}
            {item.kind === 'multiple-files' ? (
              <div className="admin__actions admin__item-actions">
                <ActionWithHint
                  hint={buttons.sortEditions.hint}
                  onClick={(event) => onEditEditions(item, event.currentTarget)}
                >
                  {buttons.sortEditions.label}
                </ActionWithHint>
              </div>
            ) : null}
          </li>
        ))}
      </ul>
    );
  }
  return (
    <section className="admin__card" aria-labelledby="admin-attention-title">
      <div className="admin__head">
        <h2 id="admin-attention-title" className="admin__title">
          Films needing attention
        </h2>
      </div>
      {body}
    </section>
  );
}

/** To do: the films needing attention. Fix match and Edit editions open from here, hosted by the layout. */
export default function AdminTodo() {
  const { settledAt, changed, openFixMatch, openEditEditions, adminSummary } = useAdminContext();
  // The count is only drawn from a summary that read cleanly; the heading never waits for it.
  const countText = adminSummary.error ? null : todoHeadingCount(adminSummary.summary);
  const attention = useAttention(`${settledAt}|${changed}`);
  useFallbackFocus(attention.data);
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
      <div className="admin__grid">
        <AttentionPanel
          attention={attention.data}
          loading={attention.loading}
          error={attention.error}
          onRetry={attention.reload}
          onFixMatch={openFixMatch}
          onEditEditions={(item, opener) =>
            openEditEditions({ id: item.filmId, title: item.title, year: item.year }, opener)
          }
        />
      </div>
    </>
  );
}
