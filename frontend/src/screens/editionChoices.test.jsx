import { fireEvent, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => vi.unstubAllGlobals());

// Heat's first edition (id 11, streams 111-114) plus a second edition with the same tracks under new ids.
function twoEditions(preferences) {
  const film = structuredClone(fixtures.heat);
  const second = structuredClone(film.editions[0]);
  second.id = 12;
  second.name = 'Director’s Cut';
  second.file = { ...second.file, id: 502 };
  second.audioStreams.forEach((s) => {
    s.id += 100;
  });
  second.subtitleStreams.forEach((s) => {
    s.id += 100;
  });
  film.editions.push(second);
  film.preferences = { ...film.preferences, ...preferences };
  return film;
}

const serve = (film) => (method, url) => (url === '/api/films/1' ? respond(film) : null);

describe('Film detail: saved choices follow the selected edition', () => {
  it("shows the equivalent of the saved subtitle and audio in the other edition's own streams", async () => {
    // Saved on edition 11: audio 112 (commentary) and subtitle 114 (forced).
    const film = twoEditions({ editionId: 12, audioStreamId: 112, subtitle: '114' });
    installFetch(serve(film));
    renderApp('/films/1');

    const subtitles = await screen.findByRole('combobox', { name: 'Subtitles' });
    expect(subtitles).toHaveValue('214');
    expect(screen.getByRole('combobox', { name: 'Audio' })).toHaveValue('212');
  });

  it('still shows the saved streams themselves on the edition they were saved on', async () => {
    const film = twoEditions({ editionId: 11, audioStreamId: 112, subtitle: '114' });
    installFetch(serve(film));
    renderApp('/films/1');

    expect(await screen.findByRole('combobox', { name: 'Subtitles' })).toHaveValue('114');
    expect(screen.getByRole('combobox', { name: 'Audio' })).toHaveValue('112');
  });

  it('shows Off when Off was saved', async () => {
    const film = twoEditions({ editionId: 12, subtitle: 'off' });
    installFetch(serve(film));
    renderApp('/films/1');

    expect(await screen.findByRole('combobox', { name: 'Subtitles' })).toHaveValue('off');
  });
});

describe('Player: saved choices follow the selected edition', () => {
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

  const mode = (src) =>
    screen.getAllByTestId('subtitle-track').find((t) => t.getAttribute('src') === src).track.mode;

  it("plays edition 12 with the saved forced track's equivalent showing, not nothing", async () => {
    const film = twoEditions({ audioStreamId: 112, subtitle: '114' });
    installFetch(serve(film));
    renderApp('/films/1/play?edition=12');
    fireEvent.loadedMetadata(await screen.findByTestId('player-video'));

    expect(mode('/api/editions/12/subtitles/4.vtt')).toBe('showing');
    expect(mode('/api/editions/12/subtitles/3.vtt')).toBe('disabled');
  });

  it('the saved full English track follows too', async () => {
    const film = twoEditions({ subtitle: '113' });
    installFetch(serve(film));
    renderApp('/films/1/play?edition=12');
    fireEvent.loadedMetadata(await screen.findByTestId('player-video'));

    expect(mode('/api/editions/12/subtitles/3.vtt')).toBe('showing');
    expect(mode('/api/editions/12/subtitles/4.vtt')).toBe('disabled');
  });
});
