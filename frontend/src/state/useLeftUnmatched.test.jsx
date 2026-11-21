import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, respond } from '../test/mockApi.jsx';
import { useLeftUnmatched } from './useLeftUnmatched.js';

afterEach(() => {
  vi.unstubAllGlobals();
});

const LIST = '/api/admin/films/left-unmatched';
const PUT = '/api/admin/films/5/put-back';
const HOME_VIDEO = { id: 5, title: 'Home video', year: 1999 };
const OTHER = { id: 6, title: 'Holiday', year: null };

function mount(over = {}) {
  const onChanged = vi.fn();
  const base = { settledAt: 's', changed: 0, onChanged };
  const hook = renderHook((props) => useLeftUnmatched(props), {
    initialProps: { ...base, ...over },
  });
  return { ...hook, onChanged, base };
}

const posts = (fetchMock) => fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST');
const callsTo = (fetchMock, url) => fetchMock.mock.calls.filter(([u]) => u === url).length;
const serve = (films, put) => (m, url) => {
  if (url === LIST) return respond(films);
  if (m === 'POST' && url === PUT) return put();
  return null;
};

describe('useLeftUnmatched', () => {
  it('reads the list and offers it as rows', async () => {
    installFetch(serve([HOME_VIDEO, OTHER], () => respond(null, 204)));
    const { result } = mount();
    expect(result.current.rows).toEqual([]);

    await waitFor(() => expect(result.current.rows).toEqual([HOME_VIDEO, OTHER]));
    expect(result.current.busy).toEqual([]);
    expect(result.current.problems).toEqual({});
  });

  it('posts the put-back route with no body, hides the film, tells the page and reports the change', async () => {
    const fetchMock = installFetch(serve([HOME_VIDEO, OTHER], () => respond(null, 204)));
    const { result, onChanged } = mount();
    await waitFor(() => expect(result.current.rows).toHaveLength(2));
    const onHidden = vi.fn();

    await act(() => result.current.putBack(HOME_VIDEO, { onHidden }));

    expect(posts(fetchMock)).toHaveLength(1);
    expect(posts(fetchMock)[0][1].body).toBeUndefined();
    expect(result.current.rows).toEqual([OTHER]);
    expect(onHidden).toHaveBeenCalledTimes(1);
    expect(onChanged).toHaveBeenCalledTimes(1);
    expect(result.current.busy).toEqual([]);
    expect(result.current.problems[5]).toBeNull();
  });

  it('keeps the film hidden while the re-read is in flight, then follows what the server says', async () => {
    let release;
    let slow = false;
    installFetch((m, url) => {
      if (url === LIST && slow) {
        return new Promise((resolve) => {
          // The server still lists the film (a scan, say): its answer is the truth.
          release = () => resolve(respond([HOME_VIDEO, OTHER]));
        });
      }
      return serve([HOME_VIDEO, OTHER], () => respond(null, 204))(m, url);
    });
    const { result, rerender, base } = mount();
    await waitFor(() => expect(result.current.rows).toHaveLength(2));
    await act(() => result.current.putBack(HOME_VIDEO));
    slow = true;

    rerender({ ...base, changed: 1 });
    // The old answer is still on screen while the new read is out, and the put back film stays hidden.
    expect(result.current.rows).toEqual([OTHER]);

    await waitFor(() => expect(release).toBeDefined());
    act(() => release());
    await waitFor(() => expect(result.current.rows).toEqual([HOME_VIDEO, OTHER]));
  });

  it('reads the list again when a scan settles', async () => {
    const fetchMock = installFetch(serve([HOME_VIDEO], () => respond(null, 204)));
    const { result, rerender, base } = mount();
    await waitFor(() => expect(result.current.rows).toHaveLength(1));

    rerender({ ...base, settledAt: 's2' });

    await waitFor(() => expect(callsTo(fetchMock, LIST)).toBe(2));
  });

  it('does nothing on a second call for the same film while its post is in flight', async () => {
    let release;
    const fetchMock = installFetch(
      serve(
        [HOME_VIDEO],
        () =>
          new Promise((resolve) => {
            release = () => resolve(respond(null, 204));
          }),
      ),
    );
    const { result, onChanged } = mount();
    await waitFor(() => expect(result.current.rows).toHaveLength(1));
    let first;
    act(() => {
      first = result.current.putBack(HOME_VIDEO);
    });
    await waitFor(() => expect(result.current.busy).toEqual([5]));

    await act(() => result.current.putBack(HOME_VIDEO));
    release();
    await act(() => first);

    expect(posts(fetchMock)).toHaveLength(1);
    expect(onChanged).toHaveBeenCalledTimes(1);
    expect(result.current.busy).toEqual([]);
  });

  it('keeps the film and says why when a scan holds the lock, without a re-read', async () => {
    installFetch(serve([HOME_VIDEO], () => respond({ error: 'A scan is running' }, 409)));
    const { result, onChanged } = mount();
    await waitFor(() => expect(result.current.rows).toHaveLength(1));
    const onHidden = vi.fn();

    await act(() => result.current.putBack(HOME_VIDEO, { onHidden }));

    expect(result.current.rows).toEqual([HOME_VIDEO]);
    expect(result.current.problems[5]).toBe('A scan is running — try again when it finishes.');
    expect(onHidden).not.toHaveBeenCalled();
    expect(onChanged).not.toHaveBeenCalled();
    expect(result.current.busy).toEqual([]);
  });

  it('says the film is no longer left unmatched on a stale 409, and asks for a re-read', async () => {
    installFetch(
      serve([HOME_VIDEO], () => respond({ error: 'This film is not left unmatched' }, 409)),
    );
    const { result, onChanged } = mount();
    await waitFor(() => expect(result.current.rows).toHaveLength(1));

    await act(() => result.current.putBack(HOME_VIDEO));

    expect(result.current.problems[5]).toBe(
      'This film is no longer left unmatched. The list is being updated.',
    );
    expect(onChanged).toHaveBeenCalledTimes(1);
  });

  it('says the film is gone on a 404, and asks for a re-read', async () => {
    installFetch(serve([HOME_VIDEO], () => respond({ error: 'No film 5' }, 404)));
    const { result, onChanged } = mount();
    await waitFor(() => expect(result.current.rows).toHaveLength(1));

    await act(() => result.current.putBack(HOME_VIDEO));

    expect(result.current.problems[5]).toBe(
      'That film is no longer in the library. The list is being updated.',
    );
    expect(onChanged).toHaveBeenCalledTimes(1);
  });

  it('can be tried again after a failure', async () => {
    let answer = respond({ error: 'x' }, 500);
    const fetchMock = installFetch(serve([HOME_VIDEO], () => answer));
    const { result } = mount();
    await waitFor(() => expect(result.current.rows).toHaveLength(1));
    await act(() => result.current.putBack(HOME_VIDEO));
    expect(result.current.problems[5]).toBe('Couldn’t put it back — try again.');

    answer = respond(null, 204);
    await act(() => result.current.putBack(HOME_VIDEO));

    expect(posts(fetchMock)).toHaveLength(2);
    expect(result.current.rows).toEqual([]);
    expect(result.current.problems[5]).toBeNull();
  });

  it('forgets a problem once a new list answer replaces the one it belonged to', async () => {
    installFetch(serve([HOME_VIDEO], () => respond({ error: 'x' }, 500)));
    const { result, rerender, base } = mount();
    await waitFor(() => expect(result.current.rows).toHaveLength(1));
    await act(() => result.current.putBack(HOME_VIDEO));
    expect(result.current.problems[5]).toBe('Couldn’t put it back — try again.');

    rerender({ ...base, changed: 1 });

    await waitFor(() => expect(result.current.problems).toEqual({}));
    expect(result.current.rows).toEqual([HOME_VIDEO]);
  });

  it('does not set state after the page has gone, but still reports the change', async () => {
    let release;
    installFetch(
      serve(
        [HOME_VIDEO],
        () =>
          new Promise((resolve) => {
            release = () => resolve(respond(null, 204));
          }),
      ),
    );
    const { result, unmount, onChanged } = mount();
    await waitFor(() => expect(result.current.rows).toHaveLength(1));
    const onHidden = vi.fn();
    let pending;
    act(() => {
      pending = result.current.putBack(HOME_VIDEO, { onHidden });
    });

    unmount();
    release();
    await pending;

    expect(onHidden).not.toHaveBeenCalled();
    expect(onChanged).toHaveBeenCalledTimes(1);
  });

  it('shows no rows and no section while the read fails', async () => {
    const fetchMock = installFetch((m, url) =>
      url === LIST ? respond({ error: 'x' }, 500) : null,
    );
    const { result } = mount();

    await waitFor(() => expect(callsTo(fetchMock, LIST)).toBe(1));

    expect(result.current.rows).toEqual([]);
  });
});
