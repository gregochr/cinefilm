import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

// The resume still: the frame at the resume point behind the startup indicator until the film plays.
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

// Heat (edition 11) saved at 3,871 s: resuming starts 10 s earlier, at 3,861 s.
const STILL_URL = '/api/editions/11/still?atMs=3861000';

async function openPlayer(path = '/films/1/play?edition=11', film) {
  const data = film ?? structuredClone(fixtures.heat);
  const fetchMock = installFetch((method, url) => (url === '/api/films/1' ? respond(data) : null));
  renderApp(path);
  const video = await screen.findByTestId('player-video');
  await waitFor(() => expect(video).toHaveAttribute('src'));
  return { video, fetchMock };
}

const stillImage = () => screen.queryByTestId('player-still');

describe('the resume still', () => {
  it('is an invisible, decorative image of the resume frame until it has loaded', async () => {
    await openPlayer();

    const still = stillImage();
    expect(still).toHaveAttribute('src', STILL_URL);
    expect(still).toHaveAttribute('alt', '');
    expect(still).toHaveAttribute('aria-hidden', 'true');
    expect(still).toHaveAttribute('decoding', 'async');
    expect(still).toHaveAttribute('fetchpriority', 'high');
    expect(still).not.toHaveClass('player__still--shown');
  });

  it('sits above the video and below the surface button and the startup indicator', async () => {
    const { video } = await openPlayer();

    const still = stillImage();
    expect(video.compareDocumentPosition(still) & Node.DOCUMENT_POSITION_FOLLOWING).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    );
    const surface = screen.getAllByRole('button', { name: 'Play' })[0]; // the surface comes first
    expect(still.compareDocumentPosition(surface) & Node.DOCUMENT_POSITION_FOLLOWING).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    );
    const indicator = screen.getByRole('status');
    expect(still.compareDocumentPosition(indicator) & Node.DOCUMENT_POSITION_FOLLOWING).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    );
  });

  it('is shown once it has loaded', async () => {
    await openPlayer();

    fireEvent.load(stillImage());

    expect(stillImage()).toHaveClass('player__still--shown');
  });

  it('is never shown, and is removed, when the image fails to load', async () => {
    await openPlayer();

    fireEvent.error(stillImage());

    expect(stillImage()).not.toBeInTheDocument();
  });

  it('is not used when the film starts from the beginning', async () => {
    await openPlayer('/films/1/play?edition=11&start=0');

    expect(stillImage()).not.toBeInTheDocument();
  });

  it('is not used when there is no saved position', async () => {
    const film = structuredClone(fixtures.heat);
    film.editions[0].positionMs = 0;
    await openPlayer('/films/1/play?edition=11', film);

    expect(stillImage()).not.toBeInTheDocument();
  });

  it('fades out when the film starts playing, then is removed for good', async () => {
    const { video } = await openPlayer();
    fireEvent.load(stillImage());
    vi.useFakeTimers();

    fireEvent.playing(video);
    expect(stillImage()).not.toHaveClass('player__still--shown');
    expect(stillImage()).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(199));
    expect(stillImage()).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(1));
    expect(stillImage()).not.toBeInTheDocument();

    // It does not come back when the video stalls and plays again (a reconnect, a restart).
    fireEvent.waiting(video);
    fireEvent.playing(video);
    expect(stillImage()).not.toBeInTheDocument();
  });

  it('ignores an image that finishes loading after the film already played', async () => {
    const { video } = await openPlayer();
    const still = stillImage();

    fireEvent.playing(video);
    expect(stillImage()).not.toBeInTheDocument();
    fireEvent.load(still);

    expect(stillImage()).not.toBeInTheDocument();
  });

  it('is removed when the viewer seeks, because the frame would be the wrong picture', async () => {
    await openPlayer();
    fireEvent.load(stillImage());

    await userEvent.click(screen.getByRole('button', { name: 'Forward 10 seconds' }));

    await waitFor(() => expect(stillImage()).not.toBeInTheDocument());
  });

  it('is removed when the film reaches the end overlay', async () => {
    const { video } = await openPlayer();
    fireEvent.load(stillImage());
    Object.defineProperty(video, 'duration', { value: 10200, configurable: true });
    fireEvent.loadedMetadata(video);
    fireEvent.timeUpdate(video); // the resume seek lands
    video.currentTime = 9900;
    fireEvent.timeUpdate(video);
    await screen.findByRole('dialog', { name: 'Heat' });

    await waitFor(() => expect(stillImage()).not.toBeInTheDocument());
  });
});
