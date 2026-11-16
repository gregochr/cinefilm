import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { useFilmFilePicker } from './useFilmFilePicker.js';

afterEach(() => vi.unstubAllGlobals());

const FILE = {
  ...fixtures.adminSubtitles[0],
  id: 21,
  kind: 'FULL',
  filmId: null,
  state: 'unmatched',
  path: 'Rashomon.en.srt',
};
const edition = (id, parts = 1) => ({ id, name: `Cut ${id}`, parts });

function serve({ editions = [edition(60)], attach } = {}) {
  const attaches = [];
  installFetch((method, url, init) => {
    if (url === '/api/admin/subtitles') return respond([FILE]);
    if (url === '/api/films/6') return respond({ id: 6, editions });
    if (url === '/api/admin/subtitles/21/attach') {
      attaches.push(JSON.parse(init.body));
      return attach ? attach() : respond(FILE);
    }
    return null;
  });
  return attaches;
}

describe('useFilmFilePicker', () => {
  it('reads the files and the editions, picking the only edition and waiting for a file', async () => {
    serve();
    const { result } = renderHook(() => useFilmFilePicker(6));
    expect(result.current.files).toBeNull();
    expect(result.current.filesLoading).toBe(true);

    await waitFor(() => expect(result.current.files).toHaveLength(1));
    await waitFor(() => expect(result.current.edition?.id).toBe(60));

    expect(result.current.filesLoading).toBe(false);
    expect(result.current.editionsLoading).toBe(false);
    // An edition alone is not a target: a file has to be chosen as well.
    expect(result.current.target).toBeNull();
    act(() => result.current.pickFile(21));
    expect(result.current.target).toEqual({ editionId: 60, part: null });
  });

  it('resets the part when another edition is picked', async () => {
    serve({ editions: [edition(60, 2), edition(61, 2)] });
    const { result } = renderHook(() => useFilmFilePicker(6));
    await waitFor(() => expect(result.current.editions).toHaveLength(2));
    act(() => result.current.pickFile(21));
    act(() => result.current.pickEdition(60));
    act(() => result.current.pickPart(2));
    expect(result.current.target).toEqual({ editionId: 60, part: 2 });

    act(() => result.current.pickEdition(61));

    expect(result.current.part).toBeNull();
    expect(result.current.target).toEqual({ editionId: 61, part: null });
  });

  it('posts the attach for the chosen file once, and resolves false for a click with no target', async () => {
    let release;
    const held = new Promise((resolve) => {
      release = resolve;
    });
    const attaches = serve({ attach: () => held.then(() => respond(FILE)) });
    const { result } = renderHook(() => useFilmFilePicker(6));
    await waitFor(() => expect(result.current.edition?.id).toBe(60));
    expect(await result.current.attach()).toBe(false);
    expect(attaches).toEqual([]);
    act(() => result.current.pickFile(21));

    let first;
    let second;
    await act(async () => {
      first = result.current.attach();
      second = result.current.attach();
    });
    expect(result.current.attaching).toBe(true);
    await act(async () => {
      release();
      await first;
    });

    expect(await second).toBe(false);
    expect(await first).toBe(true);
    expect(attaches).toEqual([{ editionId: 60, part: null }]);
    expect(result.current.attaching).toBe(false);
  });

  it('keeps the problem in words after a refused attach, and clears it on the next try', async () => {
    let fail = true;
    serve({
      attach: () => (fail ? respond({ error: 'Not that edition' }, 400) : respond(FILE)),
    });
    const { result } = renderHook(() => useFilmFilePicker(6));
    await waitFor(() => expect(result.current.edition?.id).toBe(60));
    act(() => result.current.pickFile(21));

    await act(() => result.current.attach());
    expect(result.current.problem).toBe('Not that edition');

    fail = false;
    await act(() => result.current.attach());
    expect(result.current.problem).toBeNull();
  });

  it('reports a failed files read and a failed editions read, each with a retry', async () => {
    installFetch((method, url) =>
      url === '/api/admin/subtitles' || url === '/api/films/6' ? respond(null, 500) : null,
    );
    const { result } = renderHook(() => useFilmFilePicker(6));

    await waitFor(() => expect(result.current.filesError).toBe(true));
    await waitFor(() => expect(result.current.editionsError).toBe(true));
    expect(result.current.filesLoading).toBe(false);
    expect(result.current.editionsLoading).toBe(false);
  });

  it('shares one read of the library’s files between forms for different films', async () => {
    const fetchMock = installFetch((method, url) => {
      if (url === '/api/admin/subtitles') return respond([FILE]);
      if (url === '/api/films/6') return respond({ id: 6, editions: [edition(60)] });
      if (url === '/api/films/7') return respond({ id: 7, editions: [edition(70)] });
      return null;
    });
    const files = () =>
      fetchMock.mock.calls.filter(([url]) => url === '/api/admin/subtitles').length;
    const first = renderHook(() => useFilmFilePicker(6));
    await waitFor(() => expect(first.result.current.files).toHaveLength(1));

    const second = renderHook(() => useFilmFilePicker(7));

    // The second form has the list at once (no loading state) and sent no read for it.
    expect(second.result.current.files).toHaveLength(1);
    expect(second.result.current.filesLoading).toBe(false);
    await waitFor(() => expect(second.result.current.edition?.id).toBe(70));
    expect(files()).toBe(1);
  });

  it('reads the files again for a form opened after an attach', async () => {
    const attaches = [];
    const fetchMock = installFetch((method, url, init) => {
      if (url === '/api/admin/subtitles') return respond([FILE]);
      if (url === '/api/films/6') return respond({ id: 6, editions: [edition(60)] });
      if (url === '/api/admin/subtitles/21/attach') {
        attaches.push(init.body);
        return respond(FILE);
      }
      return null;
    });
    const files = () =>
      fetchMock.mock.calls.filter(([url]) => url === '/api/admin/subtitles').length;
    const first = renderHook(() => useFilmFilePicker(6));
    await waitFor(() => expect(first.result.current.edition?.id).toBe(60));
    act(() => first.result.current.pickFile(21));
    await act(() => first.result.current.attach());
    expect(attaches).toHaveLength(1);

    const second = renderHook(() => useFilmFilePicker(6));
    await waitFor(() => expect(second.result.current.files).toHaveLength(1));

    expect(files()).toBe(2);
  });

  it('reads the files again when the version changes, without a change made here', async () => {
    const fetchMock = installFetch((method, url) => {
      if (url === '/api/admin/subtitles') return respond([FILE]);
      if (url === '/api/films/6') return respond({ id: 6, editions: [edition(60)] });
      return null;
    });
    const files = () =>
      fetchMock.mock.calls.filter(([url]) => url === '/api/admin/subtitles').length;
    const { result, rerender } = renderHook(({ version }) => useFilmFilePicker(6, { version }), {
      initialProps: { version: 'a' },
    });
    await waitFor(() => expect(result.current.files).toHaveLength(1));

    rerender({ version: 'a' });
    expect(files()).toBe(1);
    rerender({ version: 'b' });
    await waitFor(() => expect(files()).toBe(2));
  });
});
