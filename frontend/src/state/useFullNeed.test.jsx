import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { useFullNeed } from './useFullNeed.js';

afterEach(() => vi.unstubAllGlobals());

const LIST = fixtures.adminFullNeed;
const FULL = /^\/api\/admin\/films\/(\d+)\/full-need$/;

describe('useFullNeed', () => {
  it('reads the full-subtitles list and applies the row a choice answers, without reading the list again', async () => {
    const puts = [];
    const fetchMock = installFetch((method, url, init) => {
      if (!FULL.test(url)) return null;
      puts.push({ url, body: JSON.parse(init.body) });
      return respond({
        ...LIST.films[1],
        ownerChoice: 'NEEDED',
        need: 'NEEDED',
        reason: 'OWNER',
      });
    });
    const { result } = renderHook(() => useFullNeed('a'));
    await waitFor(() => expect(result.current.rows).toEqual(LIST.films));
    const reads = fetchMock.mock.calls.length;

    await act(() => result.current.choose(4, 'NEEDED'));

    expect(puts).toEqual([{ url: '/api/admin/films/4/full-need', body: { choice: 'NEEDED' } }]);
    expect(result.current.rows.find((r) => r.filmId === 4)).toMatchObject({
      need: 'NEEDED',
      ownerChoice: 'NEEDED',
    });
    expect(fetchMock.mock.calls.length).toBe(reads + 1);
    expect(result.current.saving).toEqual({});
  });

  it('says a scan is running for a 409 and keeps the row as it was', async () => {
    installFetch((method, url) => (FULL.test(url) ? respond({ error: 'scan' }, 409) : null));
    const { result } = renderHook(() => useFullNeed('a'));
    await waitFor(() => expect(result.current.rows).toEqual(LIST.films));

    await act(() => result.current.choose(4, 'NEEDED'));

    expect(result.current.problems).toEqual({ 4: 'A scan is running: try again when it finishes' });
    expect(result.current.rows).toEqual(LIST.films);
  });
});
