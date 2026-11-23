import { useCallback, useMemo, useState } from 'react';
import { adminApi } from '../api/resources.js';
import { chartSummary, chartTiles, filterByEnvironment, filterBySource } from './playbackChart.js';
import { useResource } from './useResource.js';

const DAY_MS = 86_400_000;
export const DEFAULT_DAYS = 30;

/**
 * The "How films played" card's data: the chart projection for the last `days` days (a new range reads again; the
 * environment and the source, watched or headless, are filtered here, the route has no such parameters, so switching them reads nothing), the sessions of the
 * chosen environment and source (watched people's, until the viewer asks for headless runs too), their tiles and text summary, and the selected session.
 *
 * The selection is by playback id and is the newest session until the viewer picks one. A pick that the range or
 * the environment has since removed falls back to the newest, so something is always selected while there is
 * anything to select.
 */
export function usePlaybackChart() {
  const [days, setDays] = useState(DEFAULT_DAYS);
  const [environment, setEnvironment] = useState('all');
  const [source, setSource] = useState('person');
  const [chosen, setChosen] = useState(null);
  const { data, error, loading, reload } = useResource(`admin-playback-chart-${days}`, (options) =>
    adminApi.playbackChart(new Date(Date.now() - days * DAY_MS).toISOString(), options),
  );
  const sessions = useMemo(
    () => filterBySource(filterByEnvironment(data?.sessions ?? [], environment), source),
    [data, environment, source],
  );
  const tiles = useMemo(() => chartTiles(sessions), [sessions]);
  const summary = useMemo(
    () => chartSummary(sessions, { days, environment, source }),
    [sessions, days, environment, source],
  );
  const selected = sessions.find((s) => s.playbackId === chosen) ?? sessions.at(-1) ?? null;
  const select = useCallback((playbackId) => setChosen(playbackId), []);
  return {
    days,
    setDays,
    environment,
    setEnvironment,
    source,
    setSource,
    sessions,
    tiles,
    summary,
    selected,
    select,
    loading,
    error,
    reload,
  };
}
