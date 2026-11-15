import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

const SIDECAR = (ids) => ({ source: 'SIDECAR', version: 1700000000000, sidecarIds: ids });
const DOWNLOAD = (ids) => ({ source: 'DOWNLOAD', version: 1700000000000, sidecarIds: ids });

const row = (over) => ({
  ...fixtures.adminSubtitles[5],
  filmId: 1,
  editionId: 11,
  kind: 'FULL',
  served: true,
  version: 5000,
  offsetMs: 0,
  ...over,
});

/**
 * Serves film 1 changed by `mutate`, the subtitle list `rows` and the offset route (which changes the row and moves
 * its `version` on, as the server does). `requests` records every call so a test can say exactly what went out.
 */
function serve({ mutate = () => {}, rows = [row({ id: 70 })], answer = () => null } = {}) {
  const film = structuredClone(fixtures.heat);
  mutate(film);
  const requests = [];
  installFetch((method, url, init) => {
    requests.push({ method, url, body: init?.body });
    const override = answer(method, url);
    if (override) return override;
    if (url === '/api/films/1') return respond(film);
    if (url === '/api/admin/subtitles' && method === 'GET')
      return respond(rows.map((r) => ({ ...r })));
    const put = url.match(/^\/api\/admin\/subtitles\/(\d+)\/offset$/);
    if (method === 'PUT' && put) {
      const target = rows.find((r) => r.id === Number(put[1]));
      target.offsetMs = JSON.parse(init.body).offsetMs;
      target.version += 1;
      return respond({ ...target });
    }
    return null;
  });
  return { requests, rows };
}

const keyOf = (r) => `${r.method} ${r.url}`;
const puts = (requests) =>
  requests.filter((r) => r.method === 'PUT').map((r) => `${r.url} ${r.body}`);

async function openPlayer(options) {
  const served = serve(options);
  renderApp('/films/1/play?edition=11');
  const video = await screen.findByTestId('player-video');
  await waitFor(() => expect(video).toHaveAttribute('src'));
  fireEvent.loadedMetadata(video);
  fireEvent.timeUpdate(video);
  return { ...served, video };
}

describe('the player’s subtitle timing', () => {
  // jsdom has no TextTrack, so give every <track> a stand-in that remembers its mode.
  const modes = new WeakMap();
  const original = Object.getOwnPropertyDescriptor(HTMLTrackElement.prototype, 'track');
  beforeEach(() => {
    Object.defineProperty(HTMLTrackElement.prototype, 'track', {
      configurable: true,
      get() {
        if (!modes.has(this)) modes.set(this, { mode: 'disabled' });
        return modes.get(this);
      },
    });
  });
  afterEach(() => {
    if (original) Object.defineProperty(HTMLTrackElement.prototype, 'track', original);
    else delete HTMLTrackElement.prototype.track;
  });

  const subtitlesMenu = () => screen.getByRole('menu', { name: 'Subtitles' });
  const panelButton = () => screen.getByRole('button', { name: 'Audio and subtitles' });
  const openPanel = () => userEvent.click(panelButton());
  const timingGroup = () =>
    within(subtitlesMenu()).queryByRole('group', { name: 'Subtitle timing' });
  const earlier = () =>
    within(subtitlesMenu()).getByRole('menuitem', { name: 'Show lines 0.25 seconds earlier' });
  const later = () =>
    within(subtitlesMenu()).getByRole('menuitem', { name: 'Show lines 0.25 seconds later' });
  const reset = () => within(subtitlesMenu()).getByRole('menuitem', { name: 'Reset timing' });
  const readout = () => within(subtitlesMenu()).getByRole('status');
  const fullOn =
    (ids, source = SIDECAR) =>
    (film) => {
      film.fullNeed = 'NEEDED';
      film.preferences.subtitle = 'full';
      film.editions[0].fullSubtitle = source(ids);
    };
  const fullUrl = () => screen.getByTestId('full-subtitle-track').getAttribute('src');

  describe('is offered', () => {
    it('while the owner’s own full file shows', async () => {
      await openPlayer({ mutate: fullOn([70]) });

      await openPanel();

      expect(timingGroup()).toBeInTheDocument();
      expect(await within(subtitlesMenu()).findByText('Timing: No timing change')).toBeVisible();
    });

    it('while a downloaded full file shows', async () => {
      await openPlayer({ mutate: fullOn([70], DOWNLOAD) });

      await openPanel();

      expect(timingGroup()).toBeInTheDocument();
    });

    it.each([
      ['the owner’s', SIDECAR],
      ['a downloaded', DOWNLOAD],
    ])('while Off shows %s forced file', async (_name, source) => {
      await openPlayer({
        mutate: (f) => (f.editions[0].forcedSubtitle = { ...source([70]), ownerVerified: false }),
        rows: [row({ id: 70, kind: 'FORCED' })],
      });

      await openPanel();

      expect(timingGroup()).toBeInTheDocument();
    });
  });

  describe('is not offered', () => {
    it('while an embedded subtitle is chosen, even if the edition has a file', async () => {
      await openPlayer({
        mutate: (f) => {
          fullOn([70])(f);
          f.preferences.subtitle = 113;
        },
      });

      await openPanel();

      expect(timingGroup()).not.toBeInTheDocument();
    });

    it('while Off shows only the embedded forced stream', async () => {
      const { requests } = await openPlayer({
        mutate: (f) => (f.editions[0].fullSubtitle = SIDECAR([70])),
      });

      await openPanel();

      expect(timingGroup()).not.toBeInTheDocument();
      expect(requests.some((r) => r.url === '/api/admin/subtitles')).toBe(false);
    });

    it('for a film with no subtitle file', async () => {
      await openPlayer();

      await openPanel();

      expect(timingGroup()).not.toBeInTheDocument();
    });
  });

  it('reads nothing until the panel opens', async () => {
    const { requests } = await openPlayer({ mutate: fullOn([70]) });

    expect(requests.some((r) => r.url === '/api/admin/subtitles')).toBe(false);
  });

  it('Later, Earlier and Reset send the absolute offset, and the readout follows', async () => {
    const { requests } = await openPlayer({
      mutate: fullOn([70]),
      rows: [row({ id: 70, offsetMs: 500 })],
    });
    await openPanel();
    await within(subtitlesMenu()).findByText('Timing: +0.5 s');

    await userEvent.click(later());
    await within(subtitlesMenu()).findByText('Timing: +0.75 s');
    await userEvent.click(earlier());
    await userEvent.click(earlier());
    await within(subtitlesMenu()).findByText('Timing: +0.25 s');
    await userEvent.click(reset());
    await within(subtitlesMenu()).findByText('Timing: No timing change');

    expect(puts(requests)).toEqual([
      '/api/admin/subtitles/70/offset {"offsetMs":750}',
      '/api/admin/subtitles/70/offset {"offsetMs":500}',
      '/api/admin/subtitles/70/offset {"offsetMs":250}',
      '/api/admin/subtitles/70/offset {"offsetMs":0}',
    ]);
    expect(reset()).toHaveAttribute('aria-disabled', 'true');
  });

  it('changes every part of a split film’s subtitle, each from its own offset', async () => {
    const { requests } = await openPlayer({
      mutate: fullOn([70, 71]),
      rows: [row({ id: 70, offsetMs: 1000 }), row({ id: 71, offsetMs: -500 })],
    });
    await openPanel();
    await within(subtitlesMenu()).findByText('Timing: differs by part');

    await userEvent.click(later());
    await waitFor(() => expect(puts(requests)).toHaveLength(2));
    await userEvent.click(reset());
    await within(subtitlesMenu()).findByText('Timing: No timing change');

    expect(puts(requests)).toEqual([
      '/api/admin/subtitles/70/offset {"offsetMs":1250}',
      '/api/admin/subtitles/71/offset {"offsetMs":-250}',
      '/api/admin/subtitles/70/offset {"offsetMs":0}',
      '/api/admin/subtitles/71/offset {"offsetMs":0}',
    ]);
  });

  it('reloads that one subtitle track with the new timing and nothing else about the playback changes', async () => {
    const load = vi.spyOn(HTMLMediaElement.prototype, 'load');
    const pause = vi.spyOn(HTMLMediaElement.prototype, 'pause');
    const play = vi.spyOn(HTMLMediaElement.prototype, 'play');
    const { requests, video } = await openPlayer({ mutate: fullOn([70]) });
    const src = video.getAttribute('src');
    Object.defineProperty(video, 'currentTime', { configurable: true, writable: true, value: 12 });
    await openPanel();
    await within(subtitlesMenu()).findByText('Timing: No timing change');
    const track = screen.getByTestId('full-subtitle-track');
    const urlBefore = fullUrl();
    const before = requests.length;
    const loads = load.mock.calls.length;
    const pauses = pause.mock.calls.length;
    const plays = play.mock.calls.length;

    await userEvent.click(later());
    await within(subtitlesMenu()).findByText('Timing: +0.25 s');

    // Only the subtitle requests went out: the list and the offset. No session, no stream, no film read.
    expect(requests.slice(before).map(keyOf)).toEqual([
      'GET /api/admin/subtitles',
      'PUT /api/admin/subtitles/70/offset',
    ]);
    // A new element with a new address (the server's own answer is in it), showing, in place of the old one.
    const reloaded = screen.getByTestId('full-subtitle-track');
    expect(reloaded).not.toBe(track);
    expect(track).not.toBeInTheDocument();
    expect(fullUrl()).not.toBe(urlBefore);
    expect(fullUrl()).toContain('5001_250');
    expect(reloaded.track.mode).toBe('showing');
    // The video is untouched.
    expect(video).toBeInTheDocument();
    expect(video).toHaveAttribute('src', src);
    expect(video.currentTime).toBe(12);
    expect(load.mock.calls.length).toBe(loads);
    expect(pause.mock.calls.length).toBe(pauses);
    expect(play.mock.calls.length).toBe(plays);
    load.mockRestore();
    pause.mockRestore();
    play.mockRestore();
  });

  it('a forced file’s track reloads too, and the full track is left alone', async () => {
    await openPlayer({
      mutate: (f) => {
        f.editions[0].forcedSubtitle = { ...SIDECAR([70]), ownerVerified: false };
        f.editions[0].fullSubtitle = SIDECAR([71]);
      },
      rows: [row({ id: 70, kind: 'FORCED' }), row({ id: 71 })],
    });
    await openPanel();
    await within(subtitlesMenu()).findByText('Timing: No timing change');
    const forced = screen.getByTestId('forced-subtitle-track');
    const full = screen.getByTestId('full-subtitle-track');

    await userEvent.click(later());
    await within(subtitlesMenu()).findByText('Timing: +0.25 s');

    expect(screen.getByTestId('forced-subtitle-track')).not.toBe(forced);
    expect(screen.getByTestId('forced-subtitle-track').track.mode).toBe('showing');
    expect(screen.getByTestId('full-subtitle-track')).toBe(full);
  });

  it('a double click sends one change; the buttons say they are busy and keep focus', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    const rows = [row({ id: 70 })];
    const { requests } = await openPlayer({
      mutate: fullOn([70]),
      rows,
      answer: (method) =>
        method === 'PUT'
          ? gate.then(() => respond({ ...rows[0], offsetMs: 250, version: 5001 }))
          : null,
    });
    await openPanel();
    await within(subtitlesMenu()).findByText('Timing: No timing change');
    const track = screen.getByTestId('full-subtitle-track');

    await userEvent.dblClick(later());

    await within(subtitlesMenu()).findByText('Saving…');
    expect(later()).toHaveAttribute('aria-disabled', 'true');
    expect(earlier()).toHaveAttribute('aria-disabled', 'true');
    expect(later()).toHaveFocus();
    expect(screen.getByTestId('full-subtitle-track')).toBe(track);
    release();
    await within(subtitlesMenu()).findByText('Timing: +0.25 s');
    expect(later()).toHaveFocus();
    expect(requests.filter((r) => r.method === 'PUT')).toHaveLength(1);
    expect(screen.getByTestId('full-subtitle-track')).not.toBe(track);
  });

  it('a change that finishes after the panel was closed still reloads the track, and nothing else', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    const rows = [row({ id: 70 })];
    const { video } = await openPlayer({
      mutate: fullOn([70]),
      rows,
      answer: (method) =>
        method === 'PUT'
          ? gate.then(() => respond({ ...rows[0], offsetMs: 250, version: 5001 }))
          : null,
    });
    const src = video.getAttribute('src');
    await openPanel();
    await within(subtitlesMenu()).findByText('Timing: No timing change');
    const track = screen.getByTestId('full-subtitle-track');
    await userEvent.click(later());
    await userEvent.click(panelButton());
    expect(screen.queryByRole('menu', { name: 'Subtitles' })).not.toBeInTheDocument();

    release();

    await waitFor(() => expect(screen.getByTestId('full-subtitle-track')).not.toBe(track));
    expect(fullUrl()).toContain('5001_250');
    expect(video).toHaveAttribute('src', src);
  });

  it('a late answer after the viewer switched to another subtitle file does not touch the new one', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    const rows = [row({ id: 70 }), row({ id: 71, kind: 'FORCED', offsetMs: 1000 })];
    await openPlayer({
      mutate: (f) => {
        fullOn([70])(f);
        f.editions[0].forcedSubtitle = { ...SIDECAR([71]), ownerVerified: false };
      },
      rows,
      answer: (method) =>
        method === 'PUT'
          ? gate.then(() => respond({ ...rows[0], offsetMs: 250, version: 5001 }))
          : null,
    });
    await openPanel();
    await within(subtitlesMenu()).findByText('Timing: No timing change');
    const forced = screen.getByTestId('forced-subtitle-track');
    const full = screen.getByTestId('full-subtitle-track');
    await userEvent.click(later());
    await userEvent.click(within(subtitlesMenu()).getByRole('menuitemradio', { name: /^Off/ }));
    await within(subtitlesMenu()).findByText('Timing: +1 s');

    release();

    // The full file's own track reloads (its timing did change); the forced file, which shows now, is not touched.
    await waitFor(() => expect(screen.getByTestId('full-subtitle-track')).not.toBe(full));
    expect(screen.getByTestId('forced-subtitle-track')).toBe(forced);
    expect(forced.track.mode).toBe('showing');
    expect(readout()).toHaveTextContent('Timing: +1 s');
    expect(later()).not.toHaveAttribute('aria-disabled');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('on an error shows an inline line and leaves the track, then recovers on the next try', async () => {
    let refuse = true;
    const { requests } = await openPlayer({
      mutate: fullOn([70]),
      answer: (method) =>
        method === 'PUT' && refuse ? respond({ error: 'A scan is running' }, 409) : null,
    });
    await openPanel();
    await within(subtitlesMenu()).findByText('Timing: No timing change');
    const track = screen.getByTestId('full-subtitle-track');

    await userEvent.click(later());

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Couldn’t change the timing. Try again.',
    );
    expect(screen.getByTestId('full-subtitle-track')).toBe(track);
    expect(later()).toHaveFocus();
    expect(later()).not.toHaveAttribute('aria-disabled');
    refuse = false;
    await userEvent.click(later());
    await within(subtitlesMenu()).findByText('Timing: +0.25 s');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByTestId('full-subtitle-track')).not.toBe(track);
    expect(requests.filter((r) => r.method === 'PUT')).toHaveLength(2);
  });

  it('forgets the error when the shown subtitle changes', async () => {
    await openPlayer({
      mutate: fullOn([70]),
      answer: (method) => (method === 'PUT' ? respond({ error: 'x' }, 500) : null),
    });
    await openPanel();
    await within(subtitlesMenu()).findByText('Timing: No timing change');
    await userEvent.click(later());
    await screen.findByRole('alert');

    await userEvent.click(within(subtitlesMenu()).getByRole('menuitemradio', { name: 'English' }));
    expect(timingGroup()).not.toBeInTheDocument();
    await userEvent.click(
      within(subtitlesMenu()).getByRole('menuitemradio', { name: 'English (full)' }),
    );

    expect(timingGroup()).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('says so and changes nothing when the shown file is no longer in the list', async () => {
    const { requests } = await openPlayer({ mutate: fullOn([70]), rows: [row({ id: 99 })] });
    await openPanel();

    await userEvent.click(later());

    expect(await screen.findByRole('alert')).toHaveTextContent('Couldn’t find that subtitle file.');
    expect(puts(requests)).toEqual([]);
  });
});
