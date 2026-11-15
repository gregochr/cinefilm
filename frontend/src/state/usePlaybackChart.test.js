import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { usePlaybackChart } from './usePlaybackChart.js';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const NOW = new Date('2026-11-14T12:00:00.000Z');
const CHART = '/api/admin/playback-health/chart';
// The fixture's four sessions, oldest first: Blade Runner (home), Heat (away), The Wicker Man (home), Heat (train).
const [BLADE_RUNNER, HEAT_AWAY, WICKER_MAN, HEAT_TRAIN] = fixtures.adminPlaybackChart.sessions.map(
  (s) => s.playbackId,
);

function server(body = fixtures.adminPlaybackChart) {
  const state = { body, status: 200 };
  const mock = installFetch((method, url) =>
    url.startsWith(CHART) ? respond(state.body, state.status) : null,
  );
  const charts = () => mock.mock.calls.map((c) => c[0]).filter((u) => u.startsWith(CHART));
  return { state, charts };
}

const sinceOf = (url) => new URL(url, 'http://test.local').searchParams.get('since');

async function loaded(result) {
  await waitFor(() => expect(result.current.loading).toBe(false));
}

describe('usePlaybackChart', () => {
  it('reads the last 30 days, then the last 90 after setDays(90), and reads again only for a new range', async () => {
    vi.setSystemTime(NOW);
    const api = server();
    const { result } = renderHook(() => usePlaybackChart());
    expect(result.current).toMatchObject({ days: 30, environment: 'all', loading: true });
    await loaded(result);
    expect(api.charts().map(sinceOf)).toEqual(['2026-10-15T12:00:00.000Z']);

    act(() => result.current.setDays(90));
    await waitFor(() => expect(api.charts()).toHaveLength(2));
    await loaded(result);
    expect(sinceOf(api.charts()[1])).toBe('2026-08-16T12:00:00.000Z');
    expect(result.current.days).toBe(90);

    act(() => result.current.setEnvironment('home'));
    act(() => result.current.setEnvironment('all'));
    act(() => result.current.select(BLADE_RUNNER));
    expect(api.charts()).toHaveLength(2);
  });

  it('filters by environment without a fetch, and keeps tiles and summary in step', async () => {
    const api = server();
    const { result } = renderHook(() => usePlaybackChart());
    await loaded(result);
    expect(result.current.sessions).toHaveLength(4);
    expect(result.current.tiles).toMatchObject({
      watched: 4,
      stalled: 2,
      stalledEnvironment: null,
    });
    expect(result.current.summary).toContain('the last 4 sessions in 30 days:');

    act(() => result.current.setEnvironment('train'));
    expect(result.current.sessions.map((s) => s.playbackId)).toEqual([HEAT_TRAIN]);
    expect(result.current.tiles).toMatchObject({
      watched: 1,
      stalled: 1,
      stalledEnvironment: 'train',
    });
    expect(result.current.summary).toContain('the last 1 session in 30 days on Train:');
    expect(api.charts()).toHaveLength(1);
  });

  it('selects the newest session first', async () => {
    server();
    const { result } = renderHook(() => usePlaybackChart());
    expect(result.current.selected).toBeNull();
    await loaded(result);
    expect(result.current.selected.playbackId).toBe(HEAT_TRAIN);
  });

  it('keeps a pick that survives a filter, falls back to the newest when it does not, and gets it back', async () => {
    server();
    const { result } = renderHook(() => usePlaybackChart());
    await loaded(result);
    act(() => result.current.select(BLADE_RUNNER));
    expect(result.current.selected.playbackId).toBe(BLADE_RUNNER);

    act(() => result.current.setEnvironment('home'));
    expect(result.current.selected.playbackId).toBe(BLADE_RUNNER);
    act(() => result.current.setEnvironment('away'));
    expect(result.current.selected.playbackId).toBe(HEAT_AWAY);
    act(() => result.current.setEnvironment('all'));
    expect(result.current.selected.playbackId).toBe(BLADE_RUNNER);
    act(() => result.current.select(WICKER_MAN));
    act(() => result.current.setEnvironment('train'));
    expect(result.current.selected.playbackId).toBe(HEAT_TRAIN);
  });

  it('has nothing selected, a summary that says so and zero tiles for an empty answer', async () => {
    server({ sessions: [] });
    const { result } = renderHook(() => usePlaybackChart());
    await loaded(result);
    expect(result.current.selected).toBeNull();
    expect(result.current.sessions).toEqual([]);
    expect(result.current.summary).toBe('No film sessions in the last 30 days.');
    expect(result.current.tiles).toMatchObject({ watched: 0, stalled: 0, medianStartupMs: null });
  });

  it('reports a failure, and reads again on reload', async () => {
    const api = server();
    api.state.status = 500;
    const { result } = renderHook(() => usePlaybackChart());
    await waitFor(() => expect(result.current.error).not.toBeNull());
    expect(result.current.sessions).toEqual([]);
    expect(result.current.selected).toBeNull();

    api.state.status = 200;
    act(() => result.current.reload());
    await waitFor(() => expect(result.current.sessions).toHaveLength(4));
    expect(result.current.error).toBeNull();
    expect(api.charts()).toHaveLength(2);
  });
});
