import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const FULL = { source: 'SIDECAR', version: 1700000000000 };

// Serves film 1 (Heat) changed by `mutate`; every other URL gets the defaults.
const heatWith = (mutate) => {
  const film = structuredClone(fixtures.heat);
  mutate(film);
  return (method, url) => (url === '/api/films/1' ? respond(film) : null);
};

// Heat has English audio and two embedded English subtitle streams (113 full, 114 forced). This edition also has a
// full subtitle file, and the film is a foreign one: the first audio track (the default) is French.
const foreignFilm = (f) => {
  f.fullNeed = 'NEEDED';
  f.editions[0].fullSubtitle = FULL;
  f.editions[0].audioStreams[0].language = 'French';
  f.preferences.subtitle = null;
};

const prefsSaved = (fetchMock) =>
  fetchMock.mock.calls
    .filter((c) => c[1]?.method === 'PUT' && c[0] === '/api/films/1/preferences')
    .map((c) => JSON.parse(c[1].body));

async function openPlayer(route) {
  const fetchMock = installFetch(route);
  renderApp('/films/1/play?edition=11');
  const video = await screen.findByTestId('player-video');
  await waitFor(() => expect(video).toHaveAttribute('src'));
  fireEvent.loadedMetadata(video);
  fireEvent.timeUpdate(video);
  return { video, fetchMock, player: screen.getByRole('region', { name: 'Video player' }) };
}

describe('the full English subtitle of an edition', () => {
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

  const fullTrack = () => screen.getByTestId('full-subtitle-track');
  const forcedTrack = () => screen.getByTestId('forced-subtitle-track');
  const streamTrack = (label) =>
    screen.getAllByTestId('subtitle-track').find((t) => t.getAttribute('label') === label);
  const subtitlesMenu = () => screen.getByRole('menu', { name: 'Subtitles' });
  const openPanel = () =>
    userEvent.click(screen.getByRole('button', { name: 'Audio and subtitles' }));
  const choose = (name) =>
    userEvent.click(within(subtitlesMenu()).getByRole('menuitemradio', { name }));

  it('adds an English subtitles <track> pointing at the full WebVTT with the version', async () => {
    await openPlayer(heatWith((f) => (f.editions[0].fullSubtitle = FULL)));

    const track = fullTrack();
    expect(track).toHaveAttribute('kind', 'subtitles');
    expect(track).toHaveAttribute('srclang', 'en');
    expect(track).toHaveAttribute('label', 'English (full)');
    expect(track).toHaveAttribute('src', '/api/editions/11/full-subtitles.vtt?v=1700000000000');
  });

  it('has no full track when the edition has no full subtitle', async () => {
    await openPlayer(heatWith(() => {}));

    await waitFor(() => expect(screen.getAllByTestId('subtitle-track')).toHaveLength(2));
    expect(screen.queryByTestId('full-subtitle-track')).not.toBeInTheDocument();
  });

  it('a changed version points the track at a new URL', async () => {
    await openPlayer(heatWith((f) => (f.editions[0].fullSubtitle = { ...FULL, version: 99 })));

    expect(fullTrack()).toHaveAttribute('src', '/api/editions/11/full-subtitles.vtt?v=99');
  });

  it('is an "English (full)" menu option right after Off, before the streams', async () => {
    await openPlayer(heatWith((f) => (f.editions[0].fullSubtitle = FULL)));

    await openPanel();

    const labels = within(subtitlesMenu())
      .getAllByRole('menuitemradio')
      .map((item) => item.textContent);
    expect(labels[0]).toMatch(/^Off/);
    expect(labels[1]).toBe('English (full)');
    expect(labels.slice(2)).toEqual(['English', 'English (forced)']);
  });

  it('choosing it shows the full track, hides the forced track, toasts and saves "full"', async () => {
    const { fetchMock } = await openPlayer(
      heatWith((f) => {
        f.editions[0].fullSubtitle = FULL;
        f.editions[0].forcedSubtitle = { source: 'SIDECAR', ownerVerified: false, version: 1 };
      }),
    );
    expect(forcedTrack().track.mode).toBe('showing');

    await openPanel();
    await choose('English (full)');

    expect(fullTrack().track.mode).toBe('showing');
    expect(forcedTrack().track.mode).toBe('disabled');
    expect(streamTrack('English').track.mode).toBe('disabled');
    expect(await screen.findByText('Subtitles: English (full)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Audio and subtitles' })).toHaveTextContent(
      'English (full)',
    );
    expect(prefsSaved(fetchMock)).toEqual([{ subtitle: 'full' }]);
    expect(
      within(subtitlesMenu()).getByRole('menuitemradio', { name: 'English (full)' }),
    ).toHaveAttribute('aria-checked', 'true');
  });

  it('choosing a stream hides the full track, and Off hides it too', async () => {
    await openPlayer(heatWith(foreignFilm));
    expect(fullTrack().track.mode).toBe('showing');

    await openPanel();
    await choose('English');
    expect(streamTrack('English').track.mode).toBe('showing');
    expect(fullTrack().track.mode).toBe('disabled');

    await choose('English (full)');
    expect(fullTrack().track.mode).toBe('showing');
    await choose(/^Off/);
    expect(fullTrack().track.mode).toBe('disabled');
  });

  it('the C key cycles Off → English (full) → the streams → Off', async () => {
    const { player, fetchMock } = await openPlayer(
      heatWith((f) => (f.editions[0].fullSubtitle = FULL)),
    );
    expect(player).toHaveFocus();

    await userEvent.keyboard('c');
    expect(await screen.findByText('Subtitles: English (full)')).toBeInTheDocument();
    expect(fullTrack().track.mode).toBe('showing');

    await userEvent.keyboard('c');
    expect(await screen.findByText('Subtitles: English')).toBeInTheDocument();
    await userEvent.keyboard('c');
    expect(await screen.findByText('Subtitles: English (forced)')).toBeInTheDocument();
    await userEvent.keyboard('c');
    expect(await screen.findByText('Subtitles: Off')).toBeInTheDocument();
    expect(fullTrack().track.mode).toBe('disabled');
    expect(prefsSaved(fetchMock)).toEqual([
      { subtitle: 'full' },
      { subtitle: 113 },
      { subtitle: 114 },
      { subtitle: 'off' },
    ]);
  });

  it('a film with only a full subtitle has a working captions button, not a dimmed one', async () => {
    await openPlayer(
      heatWith((f) => {
        f.editions[0].subtitleStreams = [];
        f.editions[0].fullSubtitle = FULL;
      }),
    );
    const button = screen.getByRole('button', { name: 'Audio and subtitles' });

    expect(button).not.toHaveClass('pbtn--dim');
    await userEvent.click(button);
    expect(
      within(subtitlesMenu()).getByRole('menuitemradio', { name: 'English (full)' }),
    ).toBeInTheDocument();
    expect(screen.queryByText('None for this film')).not.toBeInTheDocument();
  });

  it('a remembered "full" shows the full track from the start', async () => {
    await openPlayer(
      heatWith((f) => {
        f.editions[0].fullSubtitle = FULL;
        f.preferences.subtitle = 'full';
      }),
    );

    expect(fullTrack().track.mode).toBe('showing');
    expect(screen.getByRole('button', { name: 'Audio and subtitles' })).toHaveTextContent(
      'English (full)',
    );
  });

  it('a remembered "full" on an edition without one plays the English stream instead', async () => {
    await openPlayer(heatWith((f) => (f.preferences.subtitle = 'full')));

    expect(streamTrack('English').track.mode).toBe('showing');
    expect(screen.getByRole('button', { name: 'Audio and subtitles' })).toHaveTextContent(
      'English',
    );
  });

  describe('on by default (decision 62 and 68)', () => {
    it('is on when nothing was chosen, the film needs it and the audio is not English, and is not saved', async () => {
      const { fetchMock } = await openPlayer(heatWith(foreignFilm));

      expect(fullTrack().track.mode).toBe('showing');
      expect(screen.getByRole('button', { name: 'Audio and subtitles' })).toHaveTextContent(
        'English (full)',
      );
      await openPanel();
      expect(
        within(subtitlesMenu()).getByRole('menuitemradio', { name: 'English (full)' }),
      ).toHaveAttribute('aria-checked', 'true');
      expect(prefsSaved(fetchMock)).toEqual([]);
    });

    it('is off when the film is not marked as needing full subtitles', async () => {
      await openPlayer(
        heatWith((f) => {
          foreignFilm(f);
          f.fullNeed = 'UNSURE';
        }),
      );

      expect(fullTrack().track.mode).toBe('disabled');
      expect(screen.getByRole('button', { name: 'Audio and subtitles' })).toHaveTextContent('Off');
    });

    it('is off when the edition has no full subtitle file', async () => {
      await openPlayer(
        heatWith((f) => {
          foreignFilm(f);
          f.editions[0].fullSubtitle = null;
        }),
      );

      expect(screen.getByRole('button', { name: 'Audio and subtitles' })).toHaveTextContent('Off');
    });

    it('is off when the audio is labelled English', async () => {
      await openPlayer(
        heatWith((f) => {
          foreignFilm(f);
          f.editions[0].audioStreams[0].language = 'eng';
        }),
      );

      expect(fullTrack().track.mode).toBe('disabled');
    });

    it('stays off when the viewer has saved Off', async () => {
      await openPlayer(
        heatWith((f) => {
          foreignFilm(f);
          f.preferences.subtitle = 'off';
        }),
      );

      expect(fullTrack().track.mode).toBe('disabled');
      expect(screen.getByRole('button', { name: 'Audio and subtitles' })).toHaveTextContent('Off');
    });

    it('turning it off is a choice: Off is saved and the default does not return', async () => {
      const { fetchMock } = await openPlayer(heatWith(foreignFilm));
      await openPanel();

      await choose(/^Off/);

      expect(fullTrack().track.mode).toBe('disabled');
      expect(prefsSaved(fetchMock)).toEqual([{ subtitle: 'off' }]);
    });

    it('falls back to Off when the audio is switched to English, and back on for the foreign track', async () => {
      const { fetchMock } = await openPlayer(heatWith(foreignFilm));
      expect(fullTrack().track.mode).toBe('showing');
      await openPanel();

      await userEvent.click(screen.getByRole('menuitemradio', { name: /Commentary/ }));
      expect(fullTrack().track.mode).toBe('disabled');
      expect(screen.getByRole('button', { name: 'Audio and subtitles' })).toHaveTextContent('Off');

      await userEvent.click(screen.getByRole('menuitemradio', { name: /French/ }));
      expect(fullTrack().track.mode).toBe('showing');
      expect(prefsSaved(fetchMock).every((p) => !('subtitle' in p))).toBe(true);
    });

    it('a choice made before the audio is switched is kept', async () => {
      await openPlayer(heatWith(foreignFilm));
      await openPanel();
      await choose('English');

      await userEvent.click(screen.getByRole('menuitemradio', { name: /Commentary/ }));

      expect(streamTrack('English').track.mode).toBe('showing');
      expect(fullTrack().track.mode).toBe('disabled');
    });
  });
});
