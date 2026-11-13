import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import FullNeedSection from './FullNeedSection.jsx';

afterEach(() => vi.unstubAllGlobals());

const FULL = /^\/api\/admin\/films\/(\d+)\/full-need$/;
const LIST = fixtures.adminFullNeed;

const film = (filmId, over = {}) => ({
  ...LIST.films[0],
  filmId,
  title: `Film ${filmId}`,
  need: 'NEEDED',
  reason: 'FOREIGN_AUDIO',
  ...over,
});

/** A stateful fake of the two routes: a choice changes the film the way the server's rule would. */
function serve({ films = LIST.films, failPut = null } = {}) {
  const state = { films: structuredClone(films), puts: [], lists: 0 };
  installFetch((method, url, init) => {
    if (url === '/api/admin/subtitles/full-need') {
      state.lists += 1;
      return respond({ films: state.films, counts: {} });
    }
    const match = FULL.exec(url);
    if (!match) return null;
    const body = JSON.parse(init.body);
    state.puts.push({ filmId: Number(match[1]), body });
    if (failPut) return failPut();
    const target = state.films.find((f) => f.filmId === Number(match[1]));
    Object.assign(target, {
      ownerChoice: body.choice,
      need: body.choice ?? 'NEEDED',
      reason: body.choice ? 'OWNER' : 'FOREIGN_AUDIO',
    });
    return respond(target);
  });
  return state;
}

async function open(options) {
  const state = serve(options);
  render(<FullNeedSection refreshKey="a" />);
  await screen.findByRole('group', { name: 'Show full subtitles' });
  return state;
}

const chip = (name) =>
  within(screen.getByRole('group', { name: 'Show full subtitles' })).getByRole('button', { name });
const titles = () =>
  screen
    .queryAllByRole('group', { name: /^Full subtitles for / })
    .map((g) => g.getAttribute('aria-label').replace('Full subtitles for ', ''));
const rowOf = (title) =>
  screen.getAllByRole('listitem').find((li) => within(li).queryByText(title) !== null);

describe('Full subtitles: the list', () => {
  it('has a heading and starts on the Needed chip', async () => {
    await open();
    expect(screen.getByRole('heading', { level: 3, name: 'Full subtitles' })).toBeVisible();
    expect(chip(/^Needed/)).toHaveAttribute('aria-pressed', 'true');
    expect(titles()).toEqual(['Das Boot (1981)', 'Rashomon (1950)']);
  });

  it('shows the chips Needed, Unsure and Not needed with their counts', async () => {
    await open();
    expect(
      within(screen.getByRole('group', { name: 'Show full subtitles' }))
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Needed (2)', 'Unsure (1)', 'Not needed (1)']);
  });

  it('switches the list with the chips and marks the chosen chip pressed', async () => {
    await open();
    await userEvent.click(chip(/^Unsure/));
    expect(chip(/^Unsure/)).toHaveAttribute('aria-pressed', 'true');
    expect(chip(/^Needed/)).toHaveAttribute('aria-pressed', 'false');
    expect(titles()).toEqual(['Amélie (2001)']);
    await userEvent.click(chip(/^Not needed/));
    expect(titles()).toEqual(['Léon (1994)']);
  });

  it('says when a chip has no films', async () => {
    await open({ films: [LIST.films[0]] });
    await userEvent.click(chip(/^Unsure/));
    expect(screen.getByText('No films here.')).toBeVisible();
  });

  it('gives the reason in plain words and the audio of each edition', async () => {
    await open();
    const boot = within(rowOf('Das Boot (1981)'));
    expect(boot.getByText('Needed', { selector: 'span' })).toBeVisible();
    expect(boot.getByText('Audio is in another language')).toBeVisible();
    expect(boot.getByText('Audio: German')).toBeVisible();
    expect(within(rowOf('Rashomon (1950)')).getByText('Set by you')).toBeVisible();

    await userEvent.click(chip(/^Unsure/));
    const amelie = within(rowOf('Amélie (2001)'));
    expect(amelie.getByText('Waiting for TMDB')).toBeVisible();
    expect(
      amelie.getByText("Audio: Theatrical Cut: not labelled; Director's Cut: French"),
    ).toBeVisible();

    await userEvent.click(chip(/^Not needed/));
    const leon = within(rowOf('Léon (1994)'));
    expect(leon.getByText('Audio is English')).toBeVisible();
    expect(leon.getByText('Audio: English')).toBeVisible();
  });

  it('omits the reason line for a reason it does not know, and the audio line when no edition is present', async () => {
    await open({ films: [film(1, { year: null, reason: 'FROM_THE_FUTURE', editions: [] })] });
    expect(titles()).toEqual(['Film 1']);
    expect(screen.getByRole('listitem')).not.toHaveTextContent('FROM_THE_FUTURE');
    expect(screen.getByRole('listitem')).not.toHaveTextContent('Audio:');
  });

  it('shows 100 films at a time, and Show more adds the next 100', async () => {
    await open({ films: Array.from({ length: 250 }, (_, i) => film(i + 1)) });
    expect(titles()).toHaveLength(100);
    await userEvent.click(screen.getByRole('button', { name: 'Show more (150 left)' }));
    expect(titles()).toHaveLength(200);
    await userEvent.click(screen.getByRole('button', { name: 'Show more (50 left)' }));
    expect(titles()).toHaveLength(250);
    expect(screen.queryByRole('button', { name: /Show more/ })).not.toBeInTheDocument();
  });

  it('starts again at 100 when the chip changes', async () => {
    await open({ films: Array.from({ length: 250 }, (_, i) => film(i + 1)) });
    await userEvent.click(screen.getByRole('button', { name: 'Show more (150 left)' }));
    await userEvent.click(chip(/^Unsure/));
    await userEvent.click(chip(/^Needed/));
    expect(titles()).toHaveLength(100);
  });
});

describe('Full subtitles: unlabelled audio', () => {
  it('is Needed, with the original language assumed', async () => {
    await open({
      films: [
        film(1, {
          reason: 'ORIGINAL_AUDIO_ASSUMED',
          editions: [{ name: 'Original', audio: null }],
        }),
      ],
    });
    const row = within(rowOf('Film 1 (1981)'));
    expect(row.getByText('Needed', { selector: 'span' })).toBeVisible();
    expect(row.getByText('Audio not labelled: original language assumed')).toBeVisible();
  });
});

describe('Full subtitles: the chip it opens on', () => {
  const only = (need) => [film(1, { need }), film(2, { need })];

  it('opens on Unsure when nothing is needed', async () => {
    await open({ films: [film(1, { need: 'UNSURE' }), film(2, { need: 'NOT_NEEDED' })] });
    expect(chip(/^Unsure/)).toHaveAttribute('aria-pressed', 'true');
    expect(titles()).toEqual(['Film 1 (1981)']);
  });

  it('counts a film TMDB has not been asked about as unsure', async () => {
    await open({ films: [film(1, { need: 'UNKNOWN' }), film(2, { need: 'NOT_NEEDED' })] });
    expect(chip(/^Unsure/)).toHaveAttribute('aria-pressed', 'true');
    expect(titles()).toEqual(['Film 1 (1981)']);
  });

  it('opens on Not needed when nothing is needed or unsure', async () => {
    await open({ films: only('NOT_NEEDED') });
    expect(chip(/^Not needed/)).toHaveAttribute('aria-pressed', 'true');
    expect(titles()).toEqual(['Film 1 (1981)', 'Film 2 (1981)']);
  });

  it('stays on Needed when there are no films at all', async () => {
    await open({ films: [] });
    expect(chip(/^Needed/)).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('No films here.')).toBeVisible();
  });

  it('is not moved by a refresh that brings in a needed film', async () => {
    const state = serve({ films: only('UNSURE') });
    const { rerender } = render(<FullNeedSection refreshKey="a" />);
    await screen.findByRole('group', { name: 'Show full subtitles' });
    expect(chip(/^Unsure/)).toHaveAttribute('aria-pressed', 'true');

    state.films.push(film(3, { need: 'NEEDED' }));
    rerender(<FullNeedSection refreshKey="b" />);

    await waitFor(() => expect(chip(/^Needed/)).toHaveTextContent('(1)'));
    expect(chip(/^Unsure/)).toHaveAttribute('aria-pressed', 'true');
    expect(chip(/^Needed/)).toHaveAttribute('aria-pressed', 'false');
    expect(titles()).toEqual(['Film 1 (1981)', 'Film 2 (1981)']);
  });

  it('keeps the chip the owner chose through a refresh', async () => {
    const state = serve({ films: only('UNSURE') });
    const { rerender } = render(<FullNeedSection refreshKey="a" />);
    await screen.findByRole('group', { name: 'Show full subtitles' });
    await userEvent.click(chip(/^Not needed/));

    state.films.push(film(3, { need: 'NEEDED' }));
    rerender(<FullNeedSection refreshKey="b" />);

    await waitFor(() => expect(chip(/^Needed/)).toHaveTextContent('(1)'));
    expect(chip(/^Not needed/)).toHaveAttribute('aria-pressed', 'true');
    expect(titles()).toEqual([]);
  });
});

describe('Full subtitles: where each film’s subtitles stand', () => {
  it('says in one line whether your file plays, a download plays unchecked, or there is none', async () => {
    await open();
    expect(within(rowOf('Das Boot (1981)')).getByText('Playing: your file')).toBeVisible();
    expect(within(rowOf('Rashomon (1950)')).getByText('None yet')).toBeVisible();
    await userEvent.click(chip(/^Unsure/));
    expect(
      within(rowOf('Amélie (2001)')).getByText('Playing: download (not checked yet)'),
    ).toBeVisible();
  });

  it('has no Show files button when nobody listens', async () => {
    await open();
    expect(screen.queryByRole('button', { name: /^Show the full subtitle files/ })).toBeNull();
  });

  it('has a Show files button named for the film that tells the listener which film', async () => {
    const onShowFiles = vi.fn();
    serve();
    render(<FullNeedSection refreshKey="a" onShowFiles={onShowFiles} />);
    await screen.findByRole('group', { name: 'Show full subtitles' });
    await userEvent.click(
      screen.getByRole('button', { name: 'Show the full subtitle files of Das Boot (1981)' }),
    );

    expect(onShowFiles).toHaveBeenCalledExactlyOnceWith({ filmId: 3, title: 'Das Boot (1981)' });
  });
});

describe('Full subtitles: the owner’s choice', () => {
  const group = (name) => screen.getByRole('group', { name: `Full subtitles for ${name}` });

  it('has Needed, Not needed and Automatic with the film in their names, the current one pressed', async () => {
    await open();
    const boot = within(group('Das Boot (1981)'));
    expect(boot.getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual([
      'Full subtitles needed: Das Boot (1981)',
      'Full subtitles not needed: Das Boot (1981)',
      'Full subtitles automatic: Das Boot (1981)',
    ]);
    expect(
      boot.getByRole('button', { name: 'Full subtitles automatic: Das Boot (1981)' }),
    ).toHaveAttribute('aria-pressed', 'true');
    expect(
      within(group('Rashomon (1950)')).getByRole('button', {
        name: 'Full subtitles needed: Rashomon (1950)',
      }),
    ).toHaveAttribute('aria-pressed', 'true');
  });

  it('sends Not needed, moves the film to its chip and does not read the list again', async () => {
    const state = await open();
    await userEvent.click(
      screen.getByRole('button', { name: 'Full subtitles not needed: Das Boot (1981)' }),
    );

    await waitFor(() => expect(titles()).toEqual(['Rashomon (1950)']));
    expect(state.puts).toEqual([{ filmId: 3, body: { choice: 'NOT_NEEDED' } }]);
    expect(state.lists).toBe(1);
    expect(chip(/^Needed/)).toHaveTextContent('(1)');
    expect(chip(/^Not needed/)).toHaveTextContent('(2)');

    await userEvent.click(chip(/^Not needed/));
    expect(within(rowOf('Das Boot (1981)')).getByText('Set by you')).toBeVisible();
    expect(
      screen.getByRole('button', { name: 'Full subtitles not needed: Das Boot (1981)' }),
    ).toHaveAttribute('aria-pressed', 'true');
  });

  it('sends Needed for a film that was not needed', async () => {
    const state = await open();
    await userEvent.click(chip(/^Not needed/));
    await userEvent.click(
      screen.getByRole('button', { name: 'Full subtitles needed: Léon (1994)' }),
    );
    await waitFor(() => expect(chip(/^Not needed/)).toHaveTextContent('(0)'));
    expect(state.puts).toEqual([{ filmId: 4, body: { choice: 'NEEDED' } }]);
    expect(chip(/^Needed/)).toHaveTextContent('(3)');
  });

  it('sends null for Automatic', async () => {
    const state = await open();
    await userEvent.click(
      screen.getByRole('button', { name: 'Full subtitles automatic: Rashomon (1950)' }),
    );
    await waitFor(() => expect(state.puts).toHaveLength(1));
    expect(state.puts[0]).toEqual({ filmId: 6, body: { choice: null } });
  });

  it('keeps the row as it was and says a scan is running when the save is refused with a 409', async () => {
    await open({ failPut: () => respond({ error: 'scan' }, 409) });
    await userEvent.click(
      screen.getByRole('button', { name: 'Full subtitles not needed: Das Boot (1981)' }),
    );
    expect(await within(rowOf('Das Boot (1981)')).findByRole('status')).toHaveTextContent(
      'A scan is running: try again when it finishes',
    );
    expect(
      screen.getByRole('button', { name: 'Full subtitles automatic: Das Boot (1981)' }),
    ).toHaveAttribute('aria-pressed', 'true');
    expect(titles()).toEqual(['Das Boot (1981)', 'Rashomon (1950)']);
  });

  it('says it could not save for any other failure, on that film only', async () => {
    await open({ failPut: () => respond(null, 500) });
    await userEvent.click(
      screen.getByRole('button', { name: 'Full subtitles needed: Das Boot (1981)' }),
    );
    expect(await within(rowOf('Das Boot (1981)')).findByRole('status')).toHaveTextContent(
      "Couldn't save: try again",
    );
    expect(within(rowOf('Rashomon (1950)')).queryByRole('status')).toBeNull();
  });
});

describe('Full subtitles: loading and failing', () => {
  it('shows a static loading status until the list arrives', async () => {
    installFetch((method, url) =>
      url === '/api/admin/subtitles/full-need' ? new Promise(() => {}) : null,
    );
    render(<FullNeedSection refreshKey="a" />);
    expect(
      screen.getByRole('status', { name: 'Loading which films need full subtitles' }),
    ).toBeVisible();
    expect(screen.queryByRole('group', { name: 'Show full subtitles' })).not.toBeInTheDocument();
  });

  it('shows an error with Try again when the list cannot load, and loads it on Try again', async () => {
    let fail = true;
    installFetch((method, url) =>
      url === '/api/admin/subtitles/full-need' && fail ? respond(null, 500) : null,
    );
    render(<FullNeedSection refreshKey="a" />);
    expect(await screen.findByText(/Couldn.t load which films need full subtitles/)).toBeVisible();
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('group', { name: 'Show full subtitles' })).toBeVisible();
  });

  it('keeps the old list and shows Try again when a refresh fails', async () => {
    let fail = false;
    installFetch((method, url) =>
      url === '/api/admin/subtitles/full-need' && fail ? respond(null, 500) : null,
    );
    const { rerender } = render(<FullNeedSection refreshKey="a" />);
    await screen.findByRole('group', { name: 'Show full subtitles' });
    fail = true;
    rerender(<FullNeedSection refreshKey="b" />);
    expect(
      await screen.findByText(/Couldn.t refresh which films need full subtitles/),
    ).toBeVisible();
    expect(titles()).toEqual(['Das Boot (1981)', 'Rashomon (1950)']);
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() =>
      expect(screen.queryByText(/Couldn.t refresh which films/)).not.toBeInTheDocument(),
    );
  });
});
