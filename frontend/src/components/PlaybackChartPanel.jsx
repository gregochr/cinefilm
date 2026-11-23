import { useId } from 'react';
import { playbackChart as copy } from '../state/adminCopy.js';
import {
  percentText,
  RUNG_BUCKETS,
  STALL_COLOUR,
  stalledNote,
  startupText,
  watchedTileLabel,
} from '../state/playbackChart.js';
import { usePlaybackChart } from '../state/usePlaybackChart.js';
import AdminErrorBanner from './AdminErrorBanner.jsx';
import PlaybackColumns from './PlaybackColumns.jsx';
import SelectedSession from './SelectedSession.jsx';
import { SkeletonBlock } from './Skeleton.jsx';

function Segmented({ label, options, value, onChange }) {
  return (
    <div className="segmented pchart__segmented" role="group" aria-label={label}>
      {options.map(({ key, label: text }) => (
        <button
          key={key}
          type="button"
          className="segmented__btn"
          aria-pressed={value === key}
          onClick={() => onChange(key)}
        >
          {text}
        </button>
      ))}
    </div>
  );
}

function Tiles({ tiles, source }) {
  const note = stalledNote(tiles.stalledEnvironment);
  const items = [
    { key: 'watched', value: tiles.watched, label: watchedTileLabel(source) },
    { key: 'ok', value: tiles.withoutStall, label: copy.tiles.withoutStall, tone: 'ok' },
    {
      key: 'stalled',
      value: tiles.stalled,
      label: note ? `${copy.tiles.stalled} (${note})` : copy.tiles.stalled,
      tone: tiles.stalled > 0 ? 'err' : null,
    },
    { key: 'start', value: startupText(tiles.medianStartupMs), label: copy.tiles.typicalStart },
    { key: 'top', value: percentText(tiles.topRungPercent), label: copy.tiles.topRungs },
  ];
  return (
    <ul className="pchart__tiles">
      {items.map((item) => (
        <li key={item.key} className="pchart__tile">
          <span className={`pchart__tile-n${item.tone ? ` pchart__tile-n--${item.tone}` : ''}`}>
            {item.value}
          </span>
          <span className="pchart__tile-t">{item.label}</span>
        </li>
      ))}
    </ul>
  );
}

function Legend() {
  return (
    <p className="pchart__legend">
      {RUNG_BUCKETS.map(({ key, label, colour }) => (
        <span key={key} className="pchart__legend-item">
          <i style={{ background: colour }} aria-hidden="true" />
          {label}
        </span>
      ))}
      <span className="pchart__legend-item">
        <i className="pchart__legend-dot" style={{ background: STALL_COLOUR }} aria-hidden="true" />
        {copy.legend.stalled}
      </span>
      <span className="pchart__legend-item pchart__legend-letters">{copy.legend.letters}</span>
    </p>
  );
}

function Failed({ onRetry }) {
  return (
    <AdminErrorBanner onRetry={onRetry} retryLabel={copy.retry}>
      {copy.loadError}
    </AdminErrorBanner>
  );
}

/**
 * Playback: "How films played". The chart of the film sessions of the last 30 or 90 days (a range reads again), one
 * column each, with tiles above and the selected session's per-minute view below. The chart loads and fails on its
 * own, and so does the per-minute part.
 */
export default function PlaybackChartPanel() {
  const chart = usePlaybackChart();
  const summaryId = useId();
  let body;
  if (chart.error) {
    body = <Failed onRetry={chart.reload} />;
  } else if (chart.loading) {
    body = (
      <div role="status" aria-label={copy.loadingLabel}>
        <SkeletonBlock className="pchart__skeleton" />
      </div>
    );
  } else {
    body = (
      <>
        <Tiles tiles={chart.tiles} source={chart.source} />
        <p id={summaryId} className="sr-only">
          {chart.summary}
        </p>
        {chart.sessions.length === 0 ? (
          <p className="admin__text">{copy.empty}</p>
        ) : (
          <>
            <PlaybackColumns
              sessions={chart.sessions}
              selectedId={chart.selected?.playbackId}
              onSelect={chart.select}
              describedBy={summaryId}
            />
            <Legend />
            <p className="pchart__hint">{copy.hint}</p>
            {chart.selected ? <SelectedSession session={chart.selected} /> : null}
          </>
        )}
      </>
    );
  }
  return (
    <section className="admin__card pchart" aria-labelledby="admin-how-films-played">
      <div className="pchart__head">
        <h2 id="admin-how-films-played" className="admin__title">
          {copy.title}
        </h2>
        <span className="admin__text">{copy.why}</span>
        <div className="pchart__filters">
          <Segmented
            label={copy.rangesLabel}
            options={copy.ranges.map(({ days, label }) => ({ key: days, label }))}
            value={chart.days}
            onChange={chart.setDays}
          />
          <Segmented
            label={copy.environmentsLabel}
            options={copy.environments}
            value={chart.environment}
            onChange={chart.setEnvironment}
          />
          <Segmented
            label={copy.sourcesLabel}
            options={copy.sources}
            value={chart.source}
            onChange={chart.setSource}
          />
        </div>
      </div>
      {body}
    </section>
  );
}
