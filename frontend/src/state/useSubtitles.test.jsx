import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { useSubtitles } from './useSubtitles.js';

afterEach(() => vi.unstubAllGlobals());

describe('useSubtitles', () => {
  it('drops a second action while one is in flight, so only one request is sent', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    const fetchMock = installFetch((method, url) =>
      url === '/api/admin/subtitles/1/verify' ? gate.then(() => respond({ id: 1 })) : null,
    );
    const { result } = renderHook(() => useSubtitles('v'));
    await waitFor(() => expect(result.current.items).toEqual(fixtures.adminSubtitles));

    let first;
    let second;
    await act(async () => {
      first = result.current.verify(1);
      second = result.current.unverify(1);
      release();
      await Promise.all([first, second]);
    });

    expect(await second).toBe(false);
    const calls = fetchMock.mock.calls.filter((c) => c[0] === '/api/admin/subtitles/1/verify');
    expect(calls.map((c) => c[1].method)).toEqual(['POST']);
  });
});
