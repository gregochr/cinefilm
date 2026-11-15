import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { useSubtitlePicker } from './useSubtitlePicker.js';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const deferred = () => {
  let resolve;
  const promise = new Promise((res) => {
    resolve = res;
  });
  return { promise, resolve };
};

const search = (kind, editionId) => ({
  ...structuredClone(fixtures.adminOpenSubtitles[kind]),
  editionId,
});
const isSearch = (m, url) => m === 'GET' && url.startsWith('/api/admin/editions/');
const isDownload = (m, url) => m === 'POST' && url.endsWith('/opensubtitles/download');
const downloads = (fetchMock) => fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST');
const forcedResults = fixtures.adminOpenSubtitles.forced.results;

function setup(props = {}, route = () => null) {
  const onDownloaded = vi.fn();
  const fetchMock = installFetch(route);
  const hook = renderHook((p) => useSubtitlePicker({ onDownloaded, ...p }), {
    initialProps: { editionId: 101, kind: 'forced', ...props },
  });
  return { ...hook, fetchMock, onDownloaded };
}

describe('useSubtitlePicker: the search', () => {
  it('searches the edition for the kind on mount and gives the results in the order the server ranked them', async () => {
    const { result, fetchMock } = setup();
    expect(result.current.status).toBe('loading');
    expect(result.current.results).toEqual([]);

    await waitFor(() => expect(result.current.status).toBe('ready'));

    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/editions/101/opensubtitles?kind=forced');
    expect(result.current.results.map((r) => r.fileId)).toEqual([5012, 5011, 5013]);
    expect(result.current.downloadsLeft).toBe(3);
    expect(result.current.searchProblem).toBeNull();
  });

  it('is ready with no results when the server found none', async () => {
    const empty = { ...search('forced', 101), results: [] };
    const { result } = setup({}, (m, url) => (isSearch(m, url) ? respond(empty) : null));

    await waitFor(() => expect(result.current.status).toBe('ready'));

    expect(result.current.results).toEqual([]);
  });

  it("shows the server's own words for a failed search and searches again on retry", async () => {
    const answers = [
      respond({ error: 'OpenSubtitles is not answering. Try again in a few minutes.' }, 503),
      respond(search('forced', 101)),
    ];
    const { result } = setup({}, (m, url) => (isSearch(m, url) ? answers.shift() : null));

    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(result.current.searchProblem).toBe(
      'OpenSubtitles is not answering. Try again in a few minutes.',
    );
    expect(result.current.results).toEqual([]);

    act(() => result.current.retry());
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(result.current.searchProblem).toBeNull();
  });

  it('ignores the late answer of an older search when a newer one has been asked for', async () => {
    const older = deferred();
    const newer = deferred();
    const pending = [older, newer];
    const { result, rerender } = setup({}, (m, url) =>
      isSearch(m, url) ? pending.shift().promise : null,
    );

    rerender({ editionId: 102, kind: 'full' });
    await act(async () => newer.resolve(respond(search('full', 102))));
    await waitFor(() => expect(result.current.status).toBe('ready'));
    await act(async () => older.resolve(respond(search('forced', 101))));

    expect(result.current.results.map((r) => r.fileId)).toEqual([6012, 6011]);
  });
});

describe('useSubtitlePicker: choosing a file', () => {
  const readyHook = async (route) => {
    const hook = setup({}, route);
    await waitFor(() => expect(hook.result.current.status).toBe('ready'));
    return hook;
  };

  it('posts the file and the kind to the edition, then reports the answer with the picker still open', async () => {
    const { result, fetchMock, onDownloaded } = await readyHook();

    await act(async () => result.current.choose(forcedResults[0]));

    expect(downloads(fetchMock)).toHaveLength(1);
    const [url, init] = downloads(fetchMock)[0];
    expect(url).toBe('/api/admin/editions/101/opensubtitles/download');
    expect(JSON.parse(init.body)).toEqual({ fileId: 5012, kind: 'forced' });
    expect(onDownloaded).toHaveBeenCalledTimes(1);
    expect(onDownloaded).toHaveBeenCalledWith(
      {
        editionId: 101,
        kind: 'forced',
        fileId: 5012,
        release: 'Heat.1995.Directors.Cut.BluRay.x264',
        downloadsLeft: 2,
      },
      { open: true },
    );
  });

  it('sends one request for two clicks in the same frame and stays busy after a success', async () => {
    const held = deferred();
    const { result, fetchMock, onDownloaded } = await readyHook((m, url) =>
      isDownload(m, url) ? held.promise : null,
    );

    act(() => {
      result.current.choose(forcedResults[0]);
      result.current.choose(forcedResults[1]);
    });
    expect(result.current.downloading).toBe(5012);
    expect(downloads(fetchMock)).toHaveLength(1);

    await act(async () =>
      held.resolve(
        respond({ editionId: 101, kind: 'forced', fileId: 5012, release: 'x', downloadsLeft: 2 }),
      ),
    );
    await act(async () => result.current.choose(forcedResults[1]));

    expect(downloads(fetchMock)).toHaveLength(1);
    expect(result.current.downloading).toBe(5012);
    expect(onDownloaded).toHaveBeenCalledTimes(1);
  });

  it.each([
    [400, 'That file was not in a recent search for this edition. Search again.'],
    [409, 'A scan is running'],
    [422, 'That file has no usable cues'],
    [429, 'OpenSubtitles daily limit reached — try again tomorrow.'],
    [
      502,
      "OpenSubtitles counted this download against today's limit but could not deliver the file.",
    ],
    [503, 'OpenSubtitles is not answering. Try again in a few minutes.'],
  ])(
    'shows the server’s exact words for a %i and frees the picker for another file',
    async (status, text) => {
      const answers = [respond({ error: text }, status)];
      const { result, fetchMock, onDownloaded } = await readyHook((m, url) =>
        isDownload(m, url) ? answers.shift() : null,
      );

      await act(async () => result.current.choose(forcedResults[0]));

      expect(result.current.problem).toBe(text);
      expect(result.current.downloading).toBeNull();
      expect(onDownloaded).not.toHaveBeenCalled();
      // The failed choice did not use up the picker: the next file is sent, and clears the old problem.
      await act(async () => result.current.choose(forcedResults[1]));
      expect(downloads(fetchMock)).toHaveLength(2);
      expect(result.current.problem).toBeNull();
      expect(onDownloaded).toHaveBeenCalledTimes(1);
    },
  );

  it('says the same plain thing for a download that never reached the server', async () => {
    const { result } = await readyHook((m, url) => {
      if (!isDownload(m, url)) return null;
      throw new TypeError('Failed to fetch');
    });

    await act(async () => result.current.choose(forcedResults[0]));

    expect(result.current.problem).toBe('Couldn’t download that file. Try again.');
  });

  it('sets no state when the picker was closed first, and still tells the caller the file was saved', async () => {
    const held = deferred();
    const { result, unmount, onDownloaded } = await readyHook((m, url) =>
      isDownload(m, url) ? held.promise : null,
    );
    const errors = vi.spyOn(console, 'error').mockImplementation(() => {});

    act(() => {
      result.current.choose(forcedResults[0]);
    });
    unmount();
    await act(async () =>
      held.resolve(
        respond({ editionId: 101, kind: 'forced', fileId: 5012, release: 'x', downloadsLeft: 2 }),
      ),
    );

    expect(onDownloaded).toHaveBeenCalledTimes(1);
    expect(onDownloaded.mock.calls[0][1]).toEqual({ open: false });
    expect(errors).not.toHaveBeenCalled();
  });

  it('does not show a late failure after the picker was closed', async () => {
    const held = deferred();
    const { result, unmount, onDownloaded } = await readyHook((m, url) =>
      isDownload(m, url) ? held.promise : null,
    );
    const errors = vi.spyOn(console, 'error').mockImplementation(() => {});

    act(() => {
      result.current.choose(forcedResults[0]);
    });
    unmount();
    await act(async () => held.resolve(respond({ error: 'A scan is running' }, 409)));

    expect(onDownloaded).not.toHaveBeenCalled();
    expect(errors).not.toHaveBeenCalled();
  });

  it('calls the latest onDownloaded, not the one the picker was opened with', async () => {
    const held = deferred();
    const { result, rerender, onDownloaded } = await readyHook((m, url) =>
      isDownload(m, url) ? held.promise : null,
    );
    const later = vi.fn();

    act(() => {
      result.current.choose(forcedResults[0]);
    });
    rerender({ editionId: 101, kind: 'forced', onDownloaded: later });
    await act(async () =>
      held.resolve(
        respond({ editionId: 101, kind: 'forced', fileId: 5012, release: 'x', downloadsLeft: 2 }),
      ),
    );

    expect(later).toHaveBeenCalledTimes(1);
    expect(onDownloaded).not.toHaveBeenCalled();
  });
});
