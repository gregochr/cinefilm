import { useState } from 'react';
import { Button } from '../../components/Button.jsx';
import { SkeletonBlock } from '../../components/Skeleton.jsx';
import {
  EMPTY_NONE_TEXT,
  EMPTY_TAB_TEXT,
  emptyKind,
  emptyPendingText,
  keywordPercent,
  formatCount,
  tabCounts,
  tabRows,
} from '../../state/christmas.js';
import { useChristmas } from '../../state/useChristmas.js';
import ChristmasList from './ChristmasList.jsx';
import ChristmasStatusCard from './ChristmasStatusCard.jsx';
import ChristmasTabs, { panelDomId, tabDomId } from './ChristmasTabs.jsx';
import SectionTitle from './SectionTitle.jsx';

function ChristmasSkeleton() {
  return (
    <div className="xmas-card xmas-skeleton" role="status" aria-label="Loading">
      <div className="xmas-skeleton__head">
        <SkeletonBlock style={{ height: 20, width: '60%' }} />
        <SkeletonBlock style={{ height: 14, width: '40%' }} />
      </div>
      {Array.from({ length: 5 }, (_, i) => (
        <div className="xmas-skeleton__row" key={i}>
          <SkeletonBlock style={{ width: 40, height: 60 }} />
          <SkeletonBlock style={{ height: 16, width: '30%' }} />
          <SkeletonBlock style={{ height: 44, width: 104, borderRadius: 999 }} />
        </div>
      ))}
    </div>
  );
}

function EmptyPanel({ kind, keywords }) {
  if (kind === 'none') {
    return (
      <section className="xmas-empty" aria-label="No Christmas films">
        <h2 className="xmas-empty__title">No Christmas films yet</h2>
        <p>{EMPTY_NONE_TEXT}</p>
      </section>
    );
  }
  return (
    <section className="xmas-empty" aria-label="No Christmas films">
      <h2 className="xmas-empty__title">No Christmas films yet</h2>
      <p>{emptyPendingText(keywords)}</p>
      <div className="xmas-empty__progress">
        <span className="xmas-progress" aria-hidden="true">
          <span className="xmas-progress__fill" style={{ width: `${keywordPercent(keywords)}%` }} />
        </span>
        <span className="xmas-empty__count">
          {formatCount(keywords.fetched)} / {formatCount(keywords.total)}
        </span>
      </div>
    </section>
  );
}

/**
 * Christmas films: what Home's Christmas week will show. Adding a film and the Home-row preview arrive with the search
 * (a later slice); their place is between the status card and the tabs.
 */
export default function AdminChristmas() {
  const state = useChristmas();
  const { data, loading, error } = state;
  const [tab, setTab] = useState('inWeek');

  let body;
  if (data) {
    const empty = emptyKind(data);
    if (empty) {
      body = <EmptyPanel kind={empty} keywords={data.keywords} />;
    } else {
      const rows = tabRows(data, tab);
      body = (
        <>
          <ChristmasStatusCard data={data} />
          <ChristmasTabs counts={tabCounts(data)} selected={tab} onSelect={setTab} />
          <div
            id={panelDomId(tab)}
            role="tabpanel"
            aria-labelledby={tabDomId(tab)}
            tabIndex={-1}
            className="xmas-panel"
          >
            {rows.length > 0 ? (
              <ChristmasList rows={rows} tab={tab} max={data.limits.max} />
            ) : (
              <p className="xmas-panel__empty">{EMPTY_TAB_TEXT[tab]}</p>
            )}
          </div>
        </>
      );
    }
  } else if (error) {
    body = (
      <div className="xmas-error">
        <div className="xmas-error__banner" role="alert">
          <strong>Couldn&rsquo;t load Christmas films.</strong> CineFilm didn&rsquo;t answer. Your
          choices are safe; nothing was changed.
        </div>
        <Button
          aria-busy={loading || undefined}
          aria-disabled={loading || undefined}
          onClick={loading ? undefined : state.reload}
        >
          Try again
        </Button>
      </div>
    );
  } else if (loading) {
    body = <ChristmasSkeleton />;
  }

  return (
    <>
      <header className="xmas-head">
        <p className="xmas-head__eyebrow">Admin</p>
        <SectionTitle>Christmas films</SectionTitle>
        <p className="xmas-head__rule">
          A film counts if TMDB tags it with the &ldquo;christmas&rdquo; keyword, unless you leave
          it out.
        </p>
      </header>
      {body}
    </>
  );
}
