import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const FORCED = { source: 'SIDECAR', ownerVerified: false, version: 1700000000000 };

// Serves film 1 (Heat) changed by `mutate`; every other URL gets the defaults.
const heatWith = (mutate) => {
  const film = structuredClone(fixtures.heat);
  mutate(film);
  return (method, url) => (url === '/api/films/1' ? respond(film) : null);
};

// Heat's embedded streams are an English one (3) and an English forced one (4); this film has only the sidecar.
const sidecarOnly = (f) => {
  f.editions[0].subtitleStreams = [];
  f.editions[0].forcedSubtitle = FORCED;
};

async function openPlayer(route, path = '/films/1/play?edition=11') {
  const fetchMock = installFetch(route);
  renderApp(path);
  const video = await screen.findByTestId('player-video');
  await waitFor(() => expect(video).toHaveAttribute('src'));
  fireEvent.loadedMetadata(video);
  fireEvent.timeUpdate(video);
  return { video, fetchMock, player: screen.getByRole('region', { name: 'Video player' }) };
}

describe('the forced subtitle of an edition (a sidecar .srt)', () => {
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

  const forcedTrack = () => screen.getByTestId('forced-subtitle-track');
  const streamTrack = (label) =>
    screen.getAllByTestId('subtitle-track').find((t) => t.getAttribute('label') === label);

  it('adds one English subtitles <track> pointing at the edition’s forced WebVTT with the version', async () => {
    await openPlayer(heatWith(sidecarOnly));

    const track = forcedTrack();
    expect(track).toHaveAttribute('kind', 'subtitles');
    expect(track).toHaveAttribute('srclang', 'en');
    expect(track).toHaveAttribute('label', 'English (forced)');
    expect(track).toHaveAttribute('src', '/api/editions/11/forced-subtitles.vtt?v=1700000000000');
    expect(screen.queryAllByTestId('subtitle-track')).toHaveLength(0);
  });

  it('has no forced track when the edition has no forced subtitle', async () => {
    await openPlayer(heatWith((f) => (f.editions[0].forcedSubtitle = null)));
    expect(screen.queryByTestId('forced-subtitle-track')).not.toBeInTheDocument();

    await waitFor(() => expect(screen.getAllByTestId('subtitle-track')).toHaveLength(2));
  });

  it('shows it by itself while Subtitles is Off', async () => {
    await openPlayer(heatWith(sidecarOnly));

    expect(forcedTrack().track.mode).toBe('showing');
  });

  it('beats the embedded forced track, which stays disabled', async () => {
    await openPlayer(heatWith((f) => (f.editions[0].forcedSubtitle = FORCED)));

    expect(forcedTrack().track.mode).toBe('showing');
    expect(streamTrack('English (forced)').track.mode).toBe('disabled');
    expect(streamTrack('English').track.mode).toBe('disabled');
  });

  it('choosing a full track hides it, and going back to Off brings it back', async () => {
    await openPlayer(heatWith((f) => (f.editions[0].forcedSubtitle = FORCED)));
    await userEvent.click(screen.getByRole('button', { name: 'Audio and subtitles' }));
    await userEvent.click(
      within(screen.getByRole('menu', { name: 'Subtitles' })).getByRole('menuitemradio', {
        name: /^English$/,
      }),
    );
    expect(streamTrack('English').track.mode).toBe('showing');
    expect(forcedTrack().track.mode).toBe('disabled');

    await userEvent.click(
      within(screen.getByRole('menu', { name: 'Subtitles' })).getByRole('menuitemradio', {
        name: /^Off/,
      }),
    );
    expect(forcedTrack().track.mode).toBe('showing');
    expect(streamTrack('English').track.mode).toBe('disabled');
  });

  it('a remembered full track hides it from the start', async () => {
    await openPlayer(
      heatWith((f) => {
        f.editions[0].forcedSubtitle = FORCED;
        f.preferences.subtitle = 113;
      }),
    );

    expect(streamTrack('English').track.mode).toBe('showing');
    expect(forcedTrack().track.mode).toBe('disabled');
  });

  it('gives a film with only a sidecar a working captions button, not a dimmed one', async () => {
    await openPlayer(heatWith(sidecarOnly));
    const button = screen.getByRole('button', { name: 'Audio and subtitles' });

    expect(button).not.toHaveClass('pbtn--dim');
    expect(button).toHaveTextContent('Off');
    await userEvent.click(button);
    expect(screen.getByRole('group', { name: 'Audio and subtitles' })).toBeInTheDocument();
  });

  it('the panel of a sidecar-only film offers Off with its note, not None for this film', async () => {
    await openPlayer(heatWith(sidecarOnly));

    await userEvent.click(screen.getByRole('button', { name: 'Audio and subtitles' }));

    const menu = screen.getByRole('menu', { name: 'Subtitles' });
    expect(within(menu).getByRole('menuitemradio', { name: /^Off/ })).toHaveTextContent(
      'forced if needed',
    );
    expect(screen.queryByText('None for this film')).not.toBeInTheDocument();
  });

  it('a film with neither streams nor a forced subtitle keeps its dimmed captions button', async () => {
    await openPlayer(
      heatWith((f) => {
        f.editions[0].subtitleStreams = [];
        f.editions[0].forcedSubtitle = null;
      }),
    );

    expect(screen.getByRole('button', { name: 'Audio and subtitles' })).toHaveClass('pbtn--dim');
  });

  it('the C key answers for a film with only a sidecar, and the forced track keeps showing', async () => {
    const { player } = await openPlayer(heatWith(sidecarOnly));
    expect(player).toHaveFocus();

    await userEvent.keyboard('c');

    expect(await screen.findByText('Subtitles: Off')).toBeInTheDocument();
    expect(forcedTrack().track.mode).toBe('showing');
  });

  it('a changed version points the track at a new URL, so a nudge in Admin is fetched again', async () => {
    await openPlayer(
      heatWith((f) => {
        f.editions[0].subtitleStreams = [];
        f.editions[0].forcedSubtitle = { ...FORCED, version: 1700000099999 };
      }),
    );

    expect(forcedTrack()).toHaveAttribute(
      'src',
      '/api/editions/11/forced-subtitles.vtt?v=1700000099999',
    );
  });
});

describe('the `at` address value ("Play from here" in Admin)', () => {
  const openUnloaded = async (path) => {
    installFetch();
    renderApp(path);
    const video = await screen.findByTestId('player-video');
    await waitFor(() => expect(video).toHaveAttribute('src'));
    return video;
  };

  it('starts at that edition time, not at the resume point, with no rewind', async () => {
    await openUnloaded('/films/1/play?edition=11&at=2196778');

    expect(screen.getByText('00:36:36')).toBeInTheDocument();
    expect(screen.queryByText('01:04:21')).not.toBeInTheDocument();
  });

  it('takes the resume still from that time too', async () => {
    await openUnloaded('/films/1/play?edition=11&at=2196778');

    expect(screen.getByTestId('player-still')).toHaveAttribute(
      'src',
      '/api/editions/11/still?atMs=2196000',
    );
  });

  it('beats start=0', async () => {
    await openUnloaded('/films/1/play?edition=11&start=0&at=60000');

    expect(screen.getByText('00:01:00')).toBeInTheDocument();
  });

  it('at=0 is the start of the film', async () => {
    await openUnloaded('/films/1/play?edition=11&at=0');

    expect(screen.getByText('00:00:00')).toBeInTheDocument();
  });

  it('a time past the end of the film starts at the end', async () => {
    await openUnloaded('/films/1/play?edition=11&at=99999999');

    // Heat's edition is 10,200 s (2 h 50 m).
    expect(screen.getByText('02:50:00')).toBeInTheDocument();
  });

  it('an unusable value is ignored and the resume point applies', async () => {
    await openUnloaded('/films/1/play?edition=11&at=soon');

    expect(screen.getByText('01:04:21')).toBeInTheDocument();
  });

  it('seeks the video to that time once its metadata loads', async () => {
    const video = await openUnloaded('/films/1/play?edition=11&at=2196778');
    Object.defineProperty(video, 'duration', { value: 10200, configurable: true });

    fireEvent.loadedMetadata(video);

    expect(video.currentTime).toBeCloseTo(2196.778, 3);
  });
});
