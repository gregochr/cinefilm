import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
  delete document.fullscreenElement;
  delete document.exitFullscreen;
  delete HTMLElement.prototype.requestFullscreen;
});

// Serves film 1 (Heat) changed by `mutate`; every other URL gets the defaults.
const heatWith = (mutate) => {
  const film = structuredClone(fixtures.heat);
  mutate(film);
  return (method, url) => (url === '/api/films/1' ? respond(film) : null);
};

async function openPlayer(path = '/films/1/play?edition=11', route) {
  const fetchMock = installFetch(route);
  renderApp(path);
  const video = await screen.findByTestId('player-video');
  // No session in these tests (the default API refuses), so the player settles on direct play first.
  await waitFor(() => expect(video).toHaveAttribute('src'));
  fireEvent.loadedMetadata(video);
  fireEvent.timeUpdate(video); // the resume seek lands: from here the element's time is the position
  return { video, fetchMock, player: screen.getByRole('region', { name: 'Video player' }) };
}

const prefsSaved = (fetchMock) =>
  fetchMock.mock.calls
    .filter((c) => c[1]?.method === 'PUT' && c[0] === '/api/films/1/preferences')
    .map((c) => JSON.parse(c[1].body));

const setDuration = (video, seconds) =>
  Object.defineProperty(video, 'duration', { value: seconds, configurable: true });

describe('Player loading and choosing the edition', () => {
  it('shows a Loading status, then the player', async () => {
    installFetch();
    renderApp('/films/1/play?edition=11');
    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
    expect(await screen.findByRole('region', { name: 'Video player' })).toBeInTheDocument();
  });

  it('shows the error state with Try again', async () => {
    let fail = true;
    installFetch((m, url) => (url === '/api/films/1' && fail ? respond(null, 500) : null));
    renderApp('/films/1/play');
    expect(await screen.findByRole('alert')).toHaveTextContent('The projector jammed');
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('region', { name: 'Video player' })).toBeInTheDocument();
  });

  it('renders nothing for an empty response', async () => {
    installFetch((m, url) => (url === '/api/films/1' ? respond(null, 204) : null));
    renderApp('/films/1/play');
    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument());
    expect(screen.queryByRole('region', { name: 'Video player' })).not.toBeInTheDocument();
  });

  it('plays the edition named in the URL', async () => {
    installFetch();
    renderApp('/films/6/play?edition=62');
    const video = await screen.findByTestId('player-video');
    await waitFor(() => expect(video).toHaveAttribute('src', '/api/editions/62/stream'));
    expect(screen.getByText('1992')).toBeInTheDocument();
  });

  it('otherwise plays the remembered edition', async () => {
    installFetch(heatWith(() => {}));
    renderApp('/films/6/play');
    const video = await screen.findByTestId('player-video');
    await waitFor(() => expect(video).toHaveAttribute('src', '/api/editions/61/stream'));
  });

  it('falls back to the first edition when the remembered one is gone', async () => {
    installFetch((m, url) => {
      if (url !== '/api/films/6') return null;
      const film = structuredClone(fixtures.bladeRunner);
      film.preferences.editionId = 999;
      return respond(film);
    });
    renderApp('/films/6/play?edition=abc');
    const video = await screen.findByTestId('player-video');
    await waitFor(() => expect(video).toHaveAttribute('src', '/api/editions/61/stream'));
  });

  it('works for a film with no saved preferences', async () => {
    installFetch(
      heatWith((f) => {
        delete f.preferences;
      }),
    );
    renderApp('/films/1/play');
    const video = await screen.findByTestId('player-video');
    await waitFor(() => expect(video).toHaveAttribute('src', '/api/editions/11/stream'));
    expect(screen.getByRole('button', { name: 'Quality' })).toHaveTextContent('Auto');
  });

  it('shows the title and year, with the film year when the edition has none', async () => {
    const { player } = await openPlayer(
      '/films/1/play?edition=11',
      heatWith((f) => {
        delete f.editions[0].year;
      }),
    );
    expect(within(player).getByText('Heat')).toBeInTheDocument();
    expect(within(player).getByText('1995')).toBeInTheDocument();
  });

  it('focuses the player so the keyboard works at once', async () => {
    const { player } = await openPlayer();
    expect(player).toHaveFocus();
  });
});

describe('Player transport', () => {
  it('Play and Pause follow the video, from the button and from the surface', async () => {
    const { video } = await openPlayer();
    // loadedmetadata starts playback; the mocked play() fires "play".
    expect(video.paused).toBe(false);
    // Two controls share the name: the picture itself (first in the DOM) and the transport button.
    const [surface, button] = screen.getAllByRole('button', { name: 'Pause' });
    await userEvent.click(button);
    expect(video.paused).toBe(true);
    expect(await screen.findAllByRole('button', { name: 'Play' })).toHaveLength(2);
    await userEvent.click(surface);
    expect(video.paused).toBe(false);
    expect(await screen.findAllByRole('button', { name: 'Pause' })).toHaveLength(2);
  });

  it('Back 10 and Forward 10 seconds move the playhead', async () => {
    const { video } = await openPlayer();
    await userEvent.click(screen.getByRole('button', { name: 'Back 10 seconds' }));
    expect(video.currentTime).toBe(3851);
    await userEvent.click(screen.getByRole('button', { name: 'Forward 10 seconds' }));
    await userEvent.click(screen.getByRole('button', { name: 'Forward 10 seconds' }));
    expect(video.currentTime).toBe(3871);
  });

  it('never seeks before the start or past the end', async () => {
    const { video } = await openPlayer('/films/1/play?edition=11&start=0');
    await userEvent.click(screen.getByRole('button', { name: 'Back 10 seconds' }));
    expect(video.currentTime).toBe(0);
    const slider = screen.getByRole('slider', { name: 'Seek' });
    slider.focus();
    await userEvent.keyboard('{End}');
    expect(video.currentTime).toBe(10200);
    await userEvent.click(screen.getByRole('button', { name: 'Forward 10 seconds' }));
    expect(video.currentTime).toBe(10200);
  });

  it('the scrubber follows the video and seeks it from the keyboard', async () => {
    const { video } = await openPlayer();
    const slider = screen.getByRole('slider', { name: 'Seek' });
    video.currentTime = 100;
    fireEvent.timeUpdate(video);
    expect(slider).toHaveAttribute('aria-valuenow', '100');
    expect(slider).toHaveAttribute('aria-valuetext', '1 minute 40 seconds');
    slider.focus();
    await userEvent.keyboard('{Home}');
    expect(video.currentTime).toBe(0);
    expect(slider).toHaveAttribute('aria-valuenow', '0');
  });

  it('shows elapsed and remaining time', async () => {
    const { video } = await openPlayer();
    video.currentTime = 3600;
    fireEvent.timeUpdate(video);
    expect(screen.getByText('01:00:00')).toBeInTheDocument();
    expect(screen.getByText('−01:50:00')).toBeInTheDocument();
  });

  it('takes the duration from the video once it knows it', async () => {
    const { video } = await openPlayer();
    setDuration(video, 3600);
    fireEvent.loadedMetadata(video);
    expect(screen.getByRole('slider', { name: 'Seek' })).toHaveAttribute('aria-valuemax', '3600');
  });

  it('keeps the runtime from the API when the video reports no usable duration', async () => {
    const { video } = await openPlayer();
    setDuration(video, NaN);
    fireEvent.loadedMetadata(video);
    expect(screen.getByRole('slider', { name: 'Seek' })).toHaveAttribute('aria-valuemax', '10200');
  });

  it('ignores a video that reports zero length', async () => {
    const { video } = await openPlayer();
    setDuration(video, 0);
    fireEvent.loadedMetadata(video);
    expect(screen.getByRole('slider', { name: 'Seek' })).toHaveAttribute('aria-valuemax', '10200');
  });

  it('starts at the beginning when there is no saved position', async () => {
    const { video } = await openPlayer('/films/6/play?edition=61');
    expect(video.currentTime).toBe(0);
  });

  it('the end of the video moves the playhead to the end and shows the end card', async () => {
    const { video } = await openPlayer();
    fireEvent.ended(video);
    expect(await screen.findByRole('dialog', { name: 'Heat' })).toBeInTheDocument();
    expect(screen.getByRole('slider', { name: 'Seek' })).toHaveAttribute('aria-valuenow', '10200');
  });

  it('the buffered bar reaches the end of the range that holds the playhead', async () => {
    const { video } = await openPlayer();
    const ranges = [
      { start: 0, end: 10 },
      { start: 3800, end: 4000 },
    ];
    Object.defineProperty(video, 'buffered', {
      configurable: true,
      value: { length: ranges.length, start: (i) => ranges[i].start, end: (i) => ranges[i].end },
    });
    fireEvent.progress(video);
    const width = parseFloat(screen.getByTestId('scrubber-buffered').style.width);
    expect(width).toBeCloseTo((4000 / 10200) * 100, 1);
  });

  it('with no buffered range at the playhead, the buffered bar stops at the playhead', async () => {
    const { video } = await openPlayer();
    Object.defineProperty(video, 'buffered', {
      configurable: true,
      value: { length: 1, start: () => 0, end: () => 10 },
    });
    fireEvent.timeUpdate(video);
    const width = parseFloat(screen.getByTestId('scrubber-buffered').style.width);
    expect(width).toBeCloseTo((3861 / 10200) * 100, 1);
  });
});

describe('Player volume', () => {
  it('Mute toggles to Unmute and back, silencing the video', async () => {
    const { video } = await openPlayer();
    await userEvent.click(screen.getByRole('button', { name: 'Mute' }));
    expect(video.muted).toBe(true);
    expect(screen.getByRole('slider', { name: 'Volume' })).toHaveValue('0');
    await userEvent.click(screen.getByRole('button', { name: 'Unmute' }));
    expect(video.muted).toBe(false);
    expect(screen.getByRole('slider', { name: 'Volume' })).toHaveValue('1');
  });

  it('the volume slider sets the volume and unmutes', async () => {
    const { video } = await openPlayer();
    await userEvent.click(screen.getByRole('button', { name: 'Mute' }));
    fireEvent.change(screen.getByRole('slider', { name: 'Volume' }), { target: { value: '0.5' } });
    expect(video.volume).toBe(0.5);
    expect(video.muted).toBe(false);
    expect(screen.getByRole('button', { name: 'Mute' })).toBeInTheDocument();
  });

  it('arrow keys change the volume in tenths and stop at 0 and 1', async () => {
    const { video } = await openPlayer();
    await userEvent.keyboard('{ArrowDown}{ArrowDown}');
    expect(video.volume).toBeCloseTo(0.8);
    await userEvent.keyboard('{ArrowUp}{ArrowUp}{ArrowUp}');
    expect(video.volume).toBe(1);
    await userEvent.keyboard('{ArrowDown>11/}');
    expect(video.volume).toBe(0);
  });

  it('arrow keys on the volume slider are left to the slider', async () => {
    const { video } = await openPlayer();
    screen.getByRole('slider', { name: 'Volume' }).focus();
    await userEvent.keyboard('{ArrowLeft}');
    expect(video.currentTime).toBe(3861);
  });
});

describe('Player menus', () => {
  it('the Quality button opens and closes the menu and reports aria-expanded', async () => {
    await openPlayer();
    const button = screen.getByRole('button', { name: 'Quality' });
    expect(button).toHaveAttribute('aria-haspopup', 'menu');
    expect(button).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(button);
    expect(button).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('menu', { name: 'Quality' })).toBeInTheDocument();
    await userEvent.click(button);
    expect(screen.queryByRole('menu', { name: 'Quality' })).not.toBeInTheDocument();
    expect(button).toHaveAttribute('aria-expanded', 'false');
  });

  it('choosing a quality closes the menu, updates the pill and button, saves it and refocuses the player', async () => {
    const { fetchMock, player } = await openPlayer();
    await userEvent.click(screen.getByRole('button', { name: 'Quality' }));
    await userEvent.click(screen.getByRole('menuitemradio', { name: /^Original/ }));
    expect(screen.queryByRole('menu', { name: 'Quality' })).not.toBeInTheDocument();
    expect(screen.getByText('Original · Home')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Quality' })).toHaveTextContent('Original');
    expect(prefsSaved(fetchMock)).toEqual([{ quality: 'original' }]);
    expect(player).toHaveFocus();
  });

  it('starts on the remembered quality', async () => {
    await openPlayer(
      '/films/1/play?edition=11',
      heatWith((f) => {
        f.preferences.quality = 'original';
      }),
    );
    expect(screen.getByText('Original · Home')).toBeInTheDocument();
  });

  it('the Audio and subtitles button opens the panel and toggles aria-expanded', async () => {
    await openPlayer();
    const button = screen.getByRole('button', { name: 'Audio and subtitles' });
    expect(button).toHaveTextContent('Off');
    await userEvent.click(button);
    expect(button).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('group', { name: 'Audio and subtitles' })).toBeInTheDocument();
    await userEvent.click(button);
    expect(screen.queryByRole('group', { name: 'Audio and subtitles' })).not.toBeInTheDocument();
  });

  it('choosing an audio track saves it and marks it checked', async () => {
    const { fetchMock } = await openPlayer();
    await userEvent.click(screen.getByRole('button', { name: 'Audio and subtitles' }));
    const audio = screen.getByRole('menu', { name: 'Audio' });
    expect(within(audio).getByRole('menuitemradio', { name: /English 5.1/ })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    await userEvent.click(within(audio).getByRole('menuitemradio', { name: /Commentary/ }));
    expect(
      within(screen.getByRole('menu', { name: 'Audio' })).getByRole('menuitemradio', {
        name: /Commentary/,
      }),
    ).toHaveAttribute('aria-checked', 'true');
    expect(prefsSaved(fetchMock)).toEqual([{ audioStreamId: 112 }]);
  });

  it('starts on the remembered audio track, else the default, else the first', async () => {
    await openPlayer(
      '/films/1/play?edition=11',
      heatWith((f) => {
        f.preferences.audioStreamId = 112;
      }),
    );
    await userEvent.click(screen.getByRole('button', { name: 'Audio and subtitles' }));
    expect(screen.getByRole('menuitemradio', { name: /Commentary/ })).toHaveAttribute(
      'aria-checked',
      'true',
    );
  });

  it('falls back to the first audio track when none is default and none remembered', async () => {
    await openPlayer(
      '/films/1/play?edition=11',
      heatWith((f) => {
        f.preferences.audioStreamId = 999;
        for (const s of f.editions[0].audioStreams) s.isDefault = false;
      }),
    );
    await userEvent.click(screen.getByRole('button', { name: 'Audio and subtitles' }));
    expect(screen.getByRole('menuitemradio', { name: /English 5.1/ })).toHaveAttribute(
      'aria-checked',
      'true',
    );
  });

  it('copes with a film that has no audio tracks', async () => {
    await openPlayer(
      '/films/1/play?edition=11',
      heatWith((f) => {
        f.editions[0].audioStreams = [];
      }),
    );
    await userEvent.click(screen.getByRole('button', { name: 'Audio and subtitles' }));
    expect(within(screen.getByRole('menu', { name: 'Audio' })).queryByRole('menuitemradio')).toBe(
      null,
    );
  });

  it('choosing a subtitle shows a toast naming it, saves it and updates the button', async () => {
    const { fetchMock } = await openPlayer();
    await userEvent.click(screen.getByRole('button', { name: 'Audio and subtitles' }));
    await userEvent.click(
      within(screen.getByRole('menu', { name: 'Subtitles' })).getByRole('menuitemradio', {
        name: /^English$/,
      }),
    );
    expect(await screen.findByText('Subtitles: English')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Audio and subtitles' })).toHaveTextContent(
      'English',
    );
    expect(prefsSaved(fetchMock)).toEqual([{ subtitle: 113 }]);
    await userEvent.click(
      within(screen.getByRole('menu', { name: 'Subtitles' })).getByRole('menuitemradio', {
        name: /^Off/,
      }),
    );
    expect(await screen.findByText('Subtitles: Off')).toBeInTheDocument();
  });

  it('the subtitle toast goes away after four seconds', async () => {
    const { player } = await openPlayer();
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    expect(player).toHaveFocus();
    fireEvent.keyDown(document.activeElement, { key: 'c' });
    expect(screen.getByText('Subtitles: English')).toBeInTheDocument();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3999);
    });
    expect(screen.getByText('Subtitles: English')).toBeInTheDocument();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1);
    });
    expect(screen.queryByText('Subtitles: English')).not.toBeInTheDocument();
  });

  it('shows "None for this film" in the panel and a dimmed Off button when there are no subtitles', async () => {
    await openPlayer('/films/8/play?edition=71');
    const button = screen.getByRole('button', { name: 'Audio and subtitles' });
    expect(button).toHaveTextContent('Off');
    await userEvent.click(button);
    expect(screen.getByText('None for this film')).toBeInTheDocument();
  });

  it('shows the remembered subtitle on the button', async () => {
    await openPlayer(
      '/films/1/play?edition=11',
      heatWith((f) => {
        f.preferences.subtitle = 114;
      }),
    );
    expect(screen.getByRole('button', { name: 'Audio and subtitles' })).toHaveTextContent(
      'English (forced)',
    );
  });

  it('a click on the picture closes an open menu instead of toggling playback', async () => {
    const { video } = await openPlayer();
    await userEvent.click(screen.getByRole('button', { name: 'Quality' }));
    expect(screen.getByRole('menu', { name: 'Quality' })).toBeInTheDocument();
    const playing = !video.paused;
    await userEvent.click(screen.getAllByRole('button', { name: /^(Pause|Play)$/ })[0]);
    expect(screen.queryByRole('menu', { name: 'Quality' })).not.toBeInTheDocument();
    expect(!video.paused).toBe(playing);
  });
});

describe('Player keyboard', () => {
  it('Space toggles playback', async () => {
    const { video } = await openPlayer();
    await userEvent.keyboard(' ');
    expect(video.paused).toBe(true);
    await userEvent.keyboard(' ');
    expect(video.paused).toBe(false);
  });

  it('Space on a focused button activates the button instead of toggling playback', async () => {
    const { video } = await openPlayer();
    screen.getByRole('button', { name: 'Fullscreen' }).focus();
    await userEvent.keyboard(' ');
    expect(video.paused).toBe(false);
  });

  it('keys typed inside a menu belong to the menu', async () => {
    const { video } = await openPlayer();
    await userEvent.keyboard('q');
    expect(screen.getByRole('menuitemradio', { name: /^Auto/ })).toHaveFocus();
    await userEvent.keyboard('{ArrowDown}');
    expect(screen.getByRole('menuitemradio', { name: /^Original/ })).toHaveFocus();
    expect(video.volume).toBe(1);
  });

  it('Escape closes the menu and returns focus to the player', async () => {
    const { player } = await openPlayer();
    await userEvent.keyboard('a');
    expect(screen.getByRole('group', { name: 'Audio and subtitles' })).toBeInTheDocument();
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('group', { name: 'Audio and subtitles' })).not.toBeInTheDocument();
    expect(player).toHaveFocus();
  });

  it('Escape with nothing open does nothing', async () => {
    const { video } = await openPlayer();
    await userEvent.keyboard('{Escape}');
    expect(video.paused).toBe(false);
  });

  it('Escape leaves fullscreen before anything else', async () => {
    await openPlayer();
    document.exitFullscreen = vi.fn();
    Object.defineProperty(document, 'fullscreenElement', { value: {}, configurable: true });
    fireEvent(document, new Event('fullscreenchange'));
    await userEvent.keyboard('{Escape}');
    expect(document.exitFullscreen).toHaveBeenCalledTimes(1);
  });

  it('F and the Fullscreen button ask the player container to go fullscreen', async () => {
    const request = vi.fn();
    HTMLElement.prototype.requestFullscreen = request;
    await openPlayer();
    await userEvent.keyboard('f');
    expect(request).toHaveBeenCalledTimes(1);
    await userEvent.click(screen.getByRole('button', { name: 'Fullscreen' }));
    expect(request).toHaveBeenCalledTimes(2);
  });

  it('double-clicking the picture toggles fullscreen', async () => {
    const request = vi.fn();
    HTMLElement.prototype.requestFullscreen = request;
    await openPlayer();
    await userEvent.dblClick(screen.getAllByRole('button', { name: /^(Pause|Play)$/ })[0]);
    expect(request).toHaveBeenCalled();
  });

  it('modified keys and unknown keys do nothing', async () => {
    const { video } = await openPlayer();
    await userEvent.keyboard('{Control>}f{/Control}z');
    expect(video.paused).toBe(false);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('Q opens the quality menu and A the audio panel, one after the other', async () => {
    await openPlayer();
    await userEvent.keyboard('a');
    expect(screen.getByRole('group', { name: 'Audio and subtitles' })).toBeInTheDocument();
    await userEvent.keyboard('{Escape}q');
    expect(screen.getByRole('menu', { name: 'Quality' })).toBeInTheDocument();
  });
});

describe('Player end of film', () => {
  const reachEnd = async () => {
    const opened = await openPlayer();
    opened.video.currentTime = 9400;
    fireEvent.timeUpdate(opened.video);
    return { ...opened, dialog: await screen.findByRole('dialog', { name: 'Heat' }) };
  };

  it('Mark as watched records it and goes back to the film', async () => {
    const { fetchMock, dialog } = await reachEnd();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Mark as watched' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Heat' })).toBeInTheDocument();
    const post = fetchMock.mock.calls.find((c) => c[0] === '/api/editions/11/watched');
    expect(JSON.parse(post[1].body)).toEqual({ watched: true });
  });

  it('Back to library opens the library', async () => {
    const { dialog } = await reachEnd();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Back to library' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Library' })).toBeInTheDocument();
  });

  it('Keep watching the credits dismisses the card and refocuses the player', async () => {
    const { dialog, player } = await reachEnd();
    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Keep watching the credits' }),
    );
    expect(screen.queryByRole('dialog', { name: 'Heat' })).not.toBeInTheDocument();
    expect(player).toHaveFocus();
  });

  it('shows the director on the card, from the directors list or the legacy field', async () => {
    const { dialog } = await reachEnd();
    expect(within(dialog).getByText('1995 · Michael Mann')).toBeInTheDocument();
  });

  it('falls back to the film director field when there is no directors list', async () => {
    await openPlayer(
      '/films/1/play?edition=11',
      heatWith((f) => {
        delete f.directors;
      }),
    ).then(async ({ video }) => {
      video.currentTime = 9400;
      fireEvent.timeUpdate(video);
    });
    expect(
      within(await screen.findByRole('dialog', { name: 'Heat' })).getByText('1995 · Michael Mann'),
    ).toBeInTheDocument();
  });

  it('uses the credits marker when the edition has one', async () => {
    const { video } = await openPlayer(
      '/films/1/play?edition=11',
      heatWith((f) => {
        f.editions[0].creditsMarkerMs = 5_000_000;
      }),
    );
    video.currentTime = 4999;
    fireEvent.timeUpdate(video);
    expect(screen.queryByRole('dialog', { name: 'Heat' })).not.toBeInTheDocument();
    video.currentTime = 5000;
    fireEvent.timeUpdate(video);
    expect(await screen.findByRole('dialog', { name: 'Heat' })).toBeInTheDocument();
  });
});

describe('Player chrome', () => {
  it('Back goes to the film page', async () => {
    await openPlayer();
    await userEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Heat' })).toBeInTheDocument();
  });

  it('the hint row lists the keyboard map', async () => {
    await openPlayer();
    expect(screen.getByText('Space play/pause')).toBeInTheDocument();
    expect(screen.getByText('Controls hide after 3 s')).toBeInTheDocument();
  });

  it('pointer activity keeps the controls up, and rapid movement is throttled', async () => {
    const { video, player } = await openPlayer();
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
    fireEvent.play(video);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2900);
    });
    fireEvent.mouseMove(player);
    fireEvent.mouseMove(player);
    fireEvent.pointerDown(player);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2900);
    });
    expect(player).not.toHaveClass('player--hidden');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(200);
    });
    expect(player).toHaveClass('player--hidden');
  });
});

describe('Player and the session', () => {
  it('resumes playback after sign-in if it was playing when the session expired', async () => {
    const { video } = await openPlayer();
    expect(video.paused).toBe(false);
    act(() => {
      window.dispatchEvent(new CustomEvent('cinefilm:session-expired'));
    });
    expect(video.paused).toBe(true);
    act(() => {
      window.dispatchEvent(new CustomEvent('cinefilm:signed-in'));
    });
    await waitFor(() => expect(video.paused).toBe(false));
  });

  it('stays paused after sign-in if the viewer had paused it', async () => {
    const { video } = await openPlayer();
    await userEvent.keyboard(' ');
    expect(video.paused).toBe(true);
    act(() => {
      window.dispatchEvent(new CustomEvent('cinefilm:session-expired'));
    });
    act(() => {
      window.dispatchEvent(new CustomEvent('cinefilm:signed-in'));
    });
    expect(video.paused).toBe(true);
  });

  it('a sign-in with no earlier expiry does not start playback', async () => {
    const { video } = await openPlayer();
    await userEvent.keyboard(' ');
    act(() => {
      window.dispatchEvent(new CustomEvent('cinefilm:signed-in'));
    });
    expect(video.paused).toBe(true);
  });

  it('reports progress when the page is hidden', async () => {
    const { video, fetchMock } = await openPlayer();
    video.currentTime = 100;
    fireEvent.timeUpdate(video);
    window.dispatchEvent(new Event('pagehide'));
    const put = fetchMock.mock.calls.find((c) => c[0] === '/api/editions/11/progress');
    expect(JSON.parse(put[1].body)).toEqual({ positionMs: 100000 });
    expect(put[1].keepalive).toBe(true);
  });
});

describe('Player subtitle tracks', () => {
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

  const trackNamed = (label) =>
    screen.getAllByTestId('subtitle-track').find((t) => t.getAttribute('label') === label);
  const mode = (label) => trackNamed(label).track.mode;

  it('adds a subtitles <track> for each embedded stream, pointing at its WebVTT', async () => {
    await openPlayer();
    const tracks = screen.getAllByTestId('subtitle-track');
    expect(tracks).toHaveLength(2);
    expect(tracks.map((t) => t.getAttribute('kind'))).toEqual(['subtitles', 'subtitles']);
    expect(tracks.map((t) => t.getAttribute('label'))).toEqual(['English', 'English (forced)']);
    expect(tracks.map((t) => t.getAttribute('src'))).toEqual([
      '/api/media-files/501/subtitles/3.vtt',
      '/api/media-files/501/subtitles/4.vtt',
    ]);
  });

  it('adds no tracks for a film with no subtitle streams', async () => {
    await openPlayer('/films/8/play?edition=71');
    expect(screen.queryAllByTestId('subtitle-track')).toHaveLength(0);
  });

  it('adds no tracks when the edition has no file to read them from', async () => {
    await openPlayer(
      '/films/1/play?edition=11',
      heatWith((f) => {
        f.editions[0].file = null;
      }),
    );
    expect(screen.queryAllByTestId('subtitle-track')).toHaveLength(0);
  });

  it('turns the forced track on by itself when Subtitles is Off (decision 6)', async () => {
    await openPlayer();
    expect(mode('English (forced)')).toBe('showing');
    expect(mode('English')).toBe('disabled');
  });

  it('shows nothing when Subtitles is Off and the film has no forced track', async () => {
    await openPlayer(
      '/films/1/play?edition=11',
      heatWith((f) => {
        f.editions[0].subtitleStreams = f.editions[0].subtitleStreams.filter((s) => !s.forced);
      }),
    );
    expect(mode('English')).toBe('disabled');
  });

  it('choosing full English replaces the forced track, and going back to Off brings it back', async () => {
    await openPlayer();
    await userEvent.click(screen.getByRole('button', { name: 'Audio and subtitles' }));
    await userEvent.click(
      within(screen.getByRole('menu', { name: 'Subtitles' })).getByRole('menuitemradio', {
        name: /^English$/,
      }),
    );
    expect(mode('English')).toBe('showing');
    expect(mode('English (forced)')).toBe('disabled');

    await userEvent.click(
      within(screen.getByRole('menu', { name: 'Subtitles' })).getByRole('menuitemradio', {
        name: /^Off/,
      }),
    );
    expect(mode('English')).toBe('disabled');
    expect(mode('English (forced)')).toBe('showing');
  });

  it('shows the remembered track', async () => {
    await openPlayer(
      '/films/1/play?edition=11',
      heatWith((f) => {
        f.preferences.subtitle = 113;
      }),
    );
    expect(mode('English')).toBe('showing');
    expect(mode('English (forced)')).toBe('disabled');
  });

  it('the C key moves the showing track along with the choice', async () => {
    const { player } = await openPlayer();
    expect(player).toHaveFocus();
    await userEvent.keyboard('c');
    expect(mode('English')).toBe('showing');
    await userEvent.keyboard('c');
    expect(mode('English (forced)')).toBe('showing');
    expect(mode('English')).toBe('disabled');
    await userEvent.keyboard('c');
    expect(mode('English')).toBe('disabled');
    expect(mode('English (forced)')).toBe('showing');
  });
});
