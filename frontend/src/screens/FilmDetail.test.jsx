import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => vi.unstubAllGlobals());

// Serves film `fixture` (changed by `mutate`) at /api/films/<id>.
function filmServer(id, fixture, mutate = () => {}) {
  const film = structuredClone(fixture);
  mutate(film);
  return installFetch((method, url) => (url === `/api/films/${id}` ? respond(film) : null));
}

const putsTo = (fetchMock, path) =>
  fetchMock.mock.calls
    .filter((c) => c[1]?.method === 'PUT' && c[0] === path)
    .map((c) => JSON.parse(c[1].body));

describe('FilmDetail preferences', () => {
  it('picking a quality saves it, and unavailable qualities are disabled', async () => {
    const fetchMock = filmServer(1, fixtures.heat);
    renderApp('/films/1');
    const quality = await screen.findByLabelText('Quality');
    expect(within(quality).getByRole('option', { name: 'Auto — Original at home' })).toBeEnabled();
    expect(within(quality).getByRole('option', { name: '1080p' })).toBeDisabled();
    await userEvent.selectOptions(quality, 'Original');
    expect(quality).toHaveDisplayValue('Original');
    expect(putsTo(fetchMock, '/api/films/1/preferences')).toEqual([{ quality: 'original' }]);
  });

  it('labels a pre-made quality', async () => {
    filmServer(1, fixtures.heat, (f) => {
      f.editions[0].qualities.find((q) => q.id === '720p').premade = true;
    });
    renderApp('/films/1');
    expect(await screen.findByRole('option', { name: '720p · pre-made' })).toBeInTheDocument();
  });

  it('picking a subtitle saves its stream id, and Off saves "off"', async () => {
    const fetchMock = filmServer(1, fixtures.heat);
    renderApp('/films/1');
    const subtitles = await screen.findByLabelText('Subtitles');
    await userEvent.selectOptions(subtitles, 'English (forced)');
    expect(subtitles).toHaveDisplayValue('English (forced)');
    await userEvent.selectOptions(subtitles, 'Off · forced when needed');
    expect(putsTo(fetchMock, '/api/films/1/preferences')).toEqual([
      { subtitle: 114 },
      { subtitle: 'off' },
    ]);
  });

  it('the Off subtitle reads plainly when no stream is forced', async () => {
    filmServer(1, fixtures.heat, (f) => {
      for (const s of f.editions[0].subtitleStreams) s.forced = false;
    });
    renderApp('/films/1');
    const subtitles = await screen.findByLabelText('Subtitles');
    expect(within(subtitles).getByRole('option', { name: 'Off' })).toBeInTheDocument();
  });

  it('an edition with only a forced sidecar still has a Subtitles picker, Off · forced when needed', async () => {
    filmServer(1, fixtures.heat, (f) => {
      f.editions[0].subtitleStreams = [];
      f.editions[0].forcedSubtitle = { source: 'SIDECAR', ownerVerified: false, version: 1 };
    });
    renderApp('/films/1');
    const subtitles = await screen.findByLabelText('Subtitles');
    expect(
      within(subtitles).getByRole('option', { name: 'Off · forced when needed' }),
    ).toBeInTheDocument();
    expect(screen.queryByText('None for this film')).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Find subtitles in Admin' })).not.toBeInTheDocument();
  });

  it('streams with no forced one plus a forced sidecar still say forced when needed', async () => {
    filmServer(1, fixtures.heat, (f) => {
      for (const s of f.editions[0].subtitleStreams) s.forced = false;
      f.editions[0].forcedSubtitle = { source: 'SIDECAR', ownerVerified: true, version: 1 };
    });
    renderApp('/films/1');
    const subtitles = await screen.findByLabelText('Subtitles');
    expect(
      within(subtitles).getByRole('option', { name: 'Off · forced when needed' }),
    ).toBeInTheDocument();
  });

  it('with no streams and no sidecar it still says None for this film', async () => {
    filmServer(1, fixtures.heat, (f) => {
      f.editions[0].subtitleStreams = [];
    });
    renderApp('/films/1');
    expect(await screen.findByText('None for this film')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Find subtitles in Admin' })).toBeInTheDocument();
  });

  it('picking an audio track saves its stream id', async () => {
    const fetchMock = filmServer(1, fixtures.heat);
    renderApp('/films/1');
    const audio = await screen.findByLabelText('Audio');
    expect(audio).toHaveDisplayValue('English 5.1 · DTS');
    await userEvent.selectOptions(audio, 'Commentary — Michael Mann · AAC');
    expect(audio).toHaveDisplayValue('Commentary — Michael Mann · AAC');
    expect(putsTo(fetchMock, '/api/films/1/preferences')).toEqual([{ audioStreamId: 112 }]);
  });

  it('shows a single audio track as plain text, not a picker', async () => {
    filmServer(1, fixtures.heat, (f) => {
      f.editions[0].audioStreams.length = 1;
    });
    renderApp('/films/1');
    await screen.findByRole('heading', { name: 'Heat' });
    expect(screen.queryByLabelText('Audio')).not.toBeInTheDocument();
    expect(screen.getByText('English 5.1 · DTS')).toBeInTheDocument();
  });

  it('shows a dash when the film has no audio track at all', async () => {
    filmServer(1, fixtures.heat, (f) => {
      f.editions[0].audioStreams = [];
    });
    renderApp('/films/1');
    await screen.findByRole('heading', { name: 'Heat' });
    expect(screen.getByText('—')).toBeInTheDocument();
  });

  it('falls back to the default audio track when the remembered one is gone', async () => {
    filmServer(1, fixtures.heat, (f) => {
      f.preferences.audioStreamId = 999;
    });
    renderApp('/films/1');
    expect(await screen.findByLabelText('Audio')).toHaveDisplayValue('English 5.1 · DTS');
  });

  it('falls back to the first audio track when none is marked default', async () => {
    filmServer(1, fixtures.heat, (f) => {
      f.preferences.audioStreamId = 999;
      for (const s of f.editions[0].audioStreams) s.isDefault = false;
    });
    renderApp('/films/1');
    expect(await screen.findByLabelText('Audio')).toHaveDisplayValue('English 5.1 · DTS');
  });

  it('shows remembered quality and subtitle choices', async () => {
    filmServer(1, fixtures.heat, (f) => {
      f.preferences.quality = 'original';
      f.preferences.subtitle = 113;
    });
    renderApp('/films/1');
    expect(await screen.findByLabelText('Quality')).toHaveDisplayValue('Original');
    expect(screen.getByLabelText('Subtitles')).toHaveDisplayValue('English');
  });

  it('works for a film with no saved preferences at all', async () => {
    filmServer(1, fixtures.heat, (f) => {
      delete f.preferences;
    });
    renderApp('/films/1');
    expect(await screen.findByLabelText('Quality')).toHaveDisplayValue('Auto — Original at home');
    expect(screen.getByRole('link', { name: /Resume from 1:04:31/ })).toBeInTheDocument();
  });

  it('a failed preference save changes nothing on screen', async () => {
    installFetch((method, url) => (url === '/api/films/1/preferences' ? respond(null, 500) : null));
    renderApp('/films/1');
    await userEvent.selectOptions(await screen.findByLabelText('Quality'), 'Original');
    expect(screen.getByLabelText('Quality')).toHaveDisplayValue('Original');
  });
});

describe('FilmDetail watched state', () => {
  it('a watched edition with no position offers Play, not Resume, and can be marked unwatched', async () => {
    const fetchMock = filmServer(1, fixtures.heat, (f) => {
      f.editions[0].watched = true;
      f.editions[0].positionMs = 0;
    });
    renderApp('/films/1');
    expect(await screen.findByRole('link', { name: 'Play' })).toHaveAttribute(
      'href',
      '/films/1/play?edition=11',
    );
    expect(screen.queryByRole('link', { name: /Start from beginning/ })).not.toBeInTheDocument();
    const toggle = screen.getByRole('button', { name: 'Mark as unwatched' });
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    await userEvent.click(toggle);
    expect(await screen.findByRole('button', { name: 'Mark as watched' })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
    const post = fetchMock.mock.calls.find((c) => c[0] === '/api/editions/11/watched');
    expect(JSON.parse(post[1].body)).toEqual({ watched: false });
  });

  it('a watched edition being re-watched offers Resume and Start from beginning', async () => {
    filmServer(1, fixtures.heat, (f) => {
      f.editions[0].watched = true;
    });
    renderApp('/films/1');
    expect(await screen.findByRole('link', { name: /Resume from 1:04:31/ })).toHaveAttribute(
      'href',
      '/films/1/play?edition=11',
    );
    expect(screen.getByRole('link', { name: /Start from beginning/ })).toHaveAttribute(
      'href',
      '/films/1/play?edition=11&start=0',
    );
    expect(screen.getByRole('button', { name: 'Mark as unwatched' })).toBeInTheDocument();
  });

  it('marking a re-watched film as watched flips Resume to Play at once', async () => {
    filmServer(1, fixtures.heat, (f) => {
      f.editions[0].watched = true;
    });
    renderApp('/films/1');
    await screen.findByRole('link', { name: /Resume from 1:04:31/ });
    await userEvent.click(screen.getByRole('button', { name: 'Mark as unwatched' }));
    expect(await screen.findByRole('link', { name: 'Play' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Mark as watched' }));
    expect(await screen.findByRole('link', { name: 'Play' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Resume/ })).not.toBeInTheDocument();
  });

  it('marking a half-watched film as watched hides Resume and the time left', async () => {
    installFetch();
    renderApp('/films/1');
    await userEvent.click(await screen.findByRole('button', { name: 'Mark as watched' }));
    expect(await screen.findByRole('link', { name: 'Play' })).toBeInTheDocument();
    expect(screen.queryByText('1h 45m left')).not.toBeInTheDocument();
  });

  it('marking unwatched restarts the film from the beginning', async () => {
    installFetch();
    renderApp('/films/1');
    await userEvent.click(await screen.findByRole('button', { name: 'Mark as watched' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Mark as unwatched' }));
    expect(await screen.findByRole('link', { name: 'Play' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Resume/ })).not.toBeInTheDocument();
  });

  it('the Resume link and Start from beginning carry the edition and the dim choice', async () => {
    installFetch();
    renderApp('/films/1');
    expect(await screen.findByRole('link', { name: /Resume from 1:04:31/ })).toHaveAttribute(
      'href',
      '/films/1/play?edition=11',
    );
    expect(screen.getByRole('link', { name: /Start from beginning/ })).toHaveAttribute(
      'href',
      '/films/1/play?edition=11&start=0',
    );
    await userEvent.click(screen.getByRole('switch', { name: 'Dim the lights' }));
    expect(screen.getByRole('link', { name: /Start from beginning/ })).toHaveAttribute(
      'href',
      '/films/1/play?edition=11&dim=0&start=0',
    );
  });
});

describe('FilmDetail content', () => {
  it('shows year, runtime, genres, plot and the director and cast as links', async () => {
    installFetch();
    renderApp('/films/1');
    expect(await screen.findByRole('heading', { level: 1, name: 'Heat' })).toBeInTheDocument();
    expect(screen.getByText('1995')).toBeInTheDocument();
    expect(screen.getByText('2h 50m')).toBeInTheDocument();
    expect(screen.getByText('Crime, Thriller')).toBeInTheDocument();
    expect(screen.getByText(/A meticulous crew of professional thieves/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Directed by' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Starring' })).toBeInTheDocument();
  });

  it('leaves out genres, plot and collection a film does not have', async () => {
    filmServer(8, fixtures.wickerMan, (f) => {
      f.genres = [];
      f.overview = '';
      f.collection = null;
    });
    renderApp('/films/8');
    await screen.findByRole('heading', { name: 'The Wicker Man' });
    expect(screen.queryByText(/A devout policeman/)).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /collection$/ })).not.toBeInTheDocument();
    expect(screen.queryByText('Horror')).not.toBeInTheDocument();
  });

  it('labels a letterboxed 1920x796 file 1080p, not 796p', async () => {
    filmServer(8, fixtures.wickerMan, (f) => {
      f.editions[0].file = { width: 1920, height: 796, videoCodec: 'H.264', audioSummary: '5.1' };
    });
    renderApp('/films/8');
    expect(await screen.findByText('Original file · 1080p H.264 · 5.1')).toBeInTheDocument();
  });

  it('shows no resolution when the file width is unknown, never the height', async () => {
    filmServer(8, fixtures.wickerMan, (f) => {
      f.editions[0].file = { height: 796, videoCodec: 'H.264' };
    });
    renderApp('/films/8');
    expect(await screen.findByText('Original file · H.264')).toBeInTheDocument();
  });

  it('describes the original file, leaving out what is unknown', async () => {
    filmServer(8, fixtures.wickerMan, (f) => {
      f.editions[0].file = { width: 1280, height: 536 };
    });
    renderApp('/films/8');
    expect(await screen.findByText('Original file · 720p')).toBeInTheDocument();
  });

  it('describes an edition split across several files as one: name, part count, runtime, picture', async () => {
    filmServer(8, fixtures.wickerMan, (f) => {
      Object.assign(f.editions[0], { name: 'Extended Edition', parts: 2, runtimeMs: 14_100_000 });
      f.editions[0].file = {
        id: 9,
        width: 1920,
        height: 800,
        videoCodec: 'HEVC',
        audioSummary: '5.1',
      };
    });
    renderApp('/films/8');
    expect(
      await screen.findByText('Extended Edition · 2 parts · 3h 55m · 1080p HEVC'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/Original file/)).not.toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/part\s*\d|pt\s*\d|cd\s*\d/i);
  });

  it('copes with an edition that has no file details', async () => {
    filmServer(8, fixtures.wickerMan, (f) => {
      delete f.editions[0].file;
    });
    renderApp('/films/8');
    expect(await screen.findByText('Original file')).toBeInTheDocument();
  });

  it('leaves out the added date when it is missing or not a date', async () => {
    filmServer(8, fixtures.wickerMan, (f) => {
      f.editions[0].file.addedAt = 'not a date';
    });
    renderApp('/films/8');
    expect(await screen.findByText('Original file · 1080p HEVC · 2.0')).toBeInTheDocument();
  });

  it('titles a cast link with the character played', async () => {
    filmServer(1, fixtures.heat, (f) => {
      f.cast[0].character = 'Neil McCauley';
    });
    renderApp('/films/1');
    await screen.findByRole('heading', { name: 'Heat' });
    expect(screen.getByRole('link', { name: fixtures.heat.cast[0].person.name })).toHaveAttribute(
      'title',
      'Neil McCauley',
    );
  });
});

describe('FilmDetail states', () => {
  it('shows a Loading status, then the film', async () => {
    installFetch();
    renderApp('/films/1');
    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Heat' })).toBeInTheDocument();
    expect(screen.queryByRole('status', { name: 'Loading' })).not.toBeInTheDocument();
  });

  it('shows the error state and Try again loads the film', async () => {
    let fail = true;
    installFetch((m, url) => (url === '/api/films/1' && fail ? respond(null, 500) : null));
    renderApp('/films/1');
    expect(await screen.findByRole('alert')).toHaveTextContent('The projector jammed');
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('heading', { name: 'Heat' })).toBeInTheDocument();
  });

  it('offers the remembered edition first', async () => {
    filmServer(6, fixtures.bladeRunner, (f) => {
      f.preferences.editionId = 62;
    });
    renderApp('/films/6');
    expect(await screen.findByRole('radio', { name: /Director's Cut/ })).toBeChecked();
    expect(
      screen.getByRole('link', { name: "Resume Director's Cut from 20:00" }),
    ).toBeInTheDocument();
  });

  it('shows an edition note and falls back to the first edition for an unknown remembered one', async () => {
    filmServer(6, fixtures.bladeRunner, (f) => {
      f.preferences.editionId = 999;
    });
    renderApp('/films/6');
    expect(await screen.findByRole('radio', { name: /The Final Cut/ })).toBeChecked();
    expect(screen.getByText('Restored; the definitive version')).toBeInTheDocument();
  });
});
