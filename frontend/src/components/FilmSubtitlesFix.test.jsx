import { useState } from 'react';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import FilmSubtitlesFix from './FilmSubtitlesFix.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

const base = fixtures.adminSubtitles[5];
// The film's three files: a played download, the owner's file the download replaced, and a file attached by hand.
const rows = () => [
  {
    ...base,
    id: 6,
    origin: 'OPENSUBTITLES',
    release: 'Blade.Runner.1982.1080p',
    served: true,
    ownerAttached: false,
    replacedByDownload: false,
  },
  {
    ...base,
    id: 7,
    path: 'Blade Runner (1982)/Blade Runner.en.forced.srt',
    origin: 'FILE',
    served: false,
    ownerAttached: false,
    replacedByDownload: true,
    verified: false,
  },
  {
    ...base,
    id: 9,
    path: 'Blade Runner (1982)/Mine.en.srt',
    origin: 'FILE',
    kind: 'FULL',
    served: false,
    ownerAttached: true,
    replacedByDownload: false,
  },
  // Another film's file and an unattached one: never listed.
  { ...base, id: 30, filmId: 99, filmTitle: 'Heat', path: 'Heat/Heat.en.forced.srt' },
  { ...base, id: 31, filmId: null, state: 'unmatched', path: 'Loose.en.forced.srt' },
];

/**
 * A server that keeps the list: a delete removes the row, "use my file" marks it, detach unattaches it, so a read
 * again shows the server's truth. `answer` may return a Response to override a route.
 */
function serve({ answer = () => null } = {}) {
  const list = rows();
  const requests = [];
  const fetchMock = installFetch((method, url, init) => {
    requests.push(`${method} ${url}`);
    const override = answer(method, url, init);
    if (override) return override;
    if (url === '/api/admin/subtitles') return respond(structuredClone(list));
    const match = url.match(/^\/api\/admin\/subtitles\/(\d+)(\/\w+)?$/);
    if (!match) return null;
    const row = list.find((r) => r.id === Number(match[1]));
    if (method === 'DELETE' && !match[2]) {
      list.splice(list.indexOf(row), 1);
      return respond(null, 204);
    }
    if (match[2] === '/verify') {
      row.verified = method === 'POST';
      return respond(row);
    }
    if (match[2] === '/detach') {
      Object.assign(row, { ownerAttached: false, filmId: null, state: 'unmatched' });
      return respond(row);
    }
    if (match[2] === '/cues') {
      return respond({
        offsetMs: 0,
        cues: [{ startMs: 61000, endMs: 63000, text: '<b>Tears</b> in rain' }],
      });
    }
    return null;
  });
  return { requests, fetchMock };
}

const film = { id: 6, title: 'Blade Runner', year: 1982 };

/** Renders an opener button that shows the dialog, so focus return can be asserted. */
function Host({ onChanged, onClosed }) {
  const [opener, setOpener] = useState(null);
  return (
    <MemoryRouter>
      <button type="button" onClick={(event) => setOpener({ el: event.currentTarget })}>
        Open the fixer
      </button>
      {opener ? (
        <FilmSubtitlesFix
          film={film}
          opener={opener.el}
          onChanged={onChanged}
          onClose={() => {
            onClosed?.();
            setOpener(null);
          }}
        />
      ) : null}
    </MemoryRouter>
  );
}

async function open({ onChanged = vi.fn(), onClosed } = {}) {
  render(<Host onChanged={onChanged} onClosed={onClosed} />);
  await userEvent.click(screen.getByRole('button', { name: 'Open the fixer' }));
  const dialog = await screen.findByRole('dialog', { name: 'Blade Runner (1982)' });
  await within(dialog).findByRole('list', { name: 'Subtitle files for this film' });
  return { dialog, onChanged };
}

const names = (dialog) =>
  within(dialog)
    .getAllByRole('button')
    .map((b) => b.getAttribute('aria-label') ?? b.textContent);

describe('FilmSubtitlesFix', () => {
  it('lists only this film’s files, each with the tools that apply to it, under the renamed labels', async () => {
    serve();
    const { dialog } = await open();

    const items = within(dialog).getAllByRole('listitem');
    expect(items).toHaveLength(3);
    expect(names(items[0])).toEqual([
      'Looks right: Blade.Runner.1982.1080p',
      'Preview lines: Blade.Runner.1982.1080p',
      'Delete this download: Blade.Runner.1982.1080p',
    ]);
    expect(names(items[1])).toEqual([
      'Use my file instead: Blade Runner.en.forced.srt',
      'Preview lines: Blade Runner.en.forced.srt',
    ]);
    expect(names(items[2])).toEqual([
      'Let CineFilm decide: Mine.en.srt',
      'Preview lines: Mine.en.srt',
    ]);
    expect(within(dialog).getByRole('button', { name: 'Add my own file…' })).toBeInTheDocument();
    expect(within(dialog).queryByText('Heat/Heat.en.forced.srt')).not.toBeInTheDocument();
    expect(within(dialog).queryByText('Loose.en.forced.srt')).not.toBeInTheDocument();
  });

  it('keeps the old Admin names out of the panel', async () => {
    serve();
    const { dialog } = await open();

    for (const old of [/Show cues/, /Remove download/, /Back to automatic/]) {
      expect(within(dialog).queryByRole('button', { name: old })).not.toBeInTheDocument();
    }
  });

  it('shows each tool’s hint under it', async () => {
    serve();
    const { dialog } = await open();

    expect(
      within(dialog).getByRole('button', { name: /Use my file instead/ }),
    ).toHaveAccessibleDescription('Plays your own file rather than the download.');
    expect(
      within(dialog).getByRole('button', { name: /Delete this download/ }),
    ).toHaveAccessibleDescription('Deletes the downloaded file; CineFilm won’t fetch it again.');
    expect(
      within(dialog).getByRole('button', { name: 'Add my own file…' }),
    ).toHaveAccessibleDescription('Picks one of your subtitle files for this film');
  });

  describe('Looks right', () => {
    const looksRight = (scope) => within(scope).queryAllByRole('button', { name: /^Looks right/ });

    it('is offered on a forced file that is not checked, and on no other: not the replaced file, not a full one', async () => {
      serve();
      const { dialog } = await open();

      const items = within(dialog).getAllByRole('listitem');
      expect(looksRight(items[0])).toHaveLength(1);
      // The replaced owner file has "Use my file instead" in its place, never both.
      expect(within(items[1]).getByRole('button', { name: /^Use my file instead/ })).toBeVisible();
      expect(looksRight(items[1])).toHaveLength(0);
      // A full file has no checks.
      expect(looksRight(items[2])).toHaveLength(0);
      expect(looksRight(dialog)).toHaveLength(1);
    });

    it('shows its hint under it', async () => {
      serve();
      const { dialog } = await open();

      expect(looksRight(dialog)[0]).toHaveAccessibleDescription(
        'Marks the lines as checked: you’ve seen they’re right.',
      );
    });

    it('posts verify once, says so in a status that takes focus, and the file reads as checked with an Undo', async () => {
      const { requests } = serve();
      const { dialog, onChanged } = await open();

      await userEvent.click(looksRight(dialog)[0]);

      await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
      const notice = await within(dialog).findByText('Marked as checked.');
      await waitFor(() => expect(notice).toHaveFocus());
      expect(requests.filter((r) => r.startsWith('POST'))).toEqual([
        'POST /api/admin/subtitles/6/verify',
      ]);
      expect(
        await within(dialog).findByText('You checked this: the lines look right.'),
      ).toBeVisible();
      expect(within(dialog).getByRole('button', { name: /^Undo/ })).toBeVisible();
      expect(looksRight(dialog)).toHaveLength(0);
    });

    it('Undo sends DELETE on verify and brings Looks right back', async () => {
      const { requests } = serve();
      const { dialog } = await open();
      await userEvent.click(looksRight(dialog)[0]);
      await userEvent.click(await within(dialog).findByRole('button', { name: /^Undo/ }));

      const notice = await within(dialog).findByText('The check is taken away.');
      await waitFor(() => expect(notice).toHaveFocus());
      expect(requests.filter((r) => r.startsWith('DELETE'))).toEqual([
        'DELETE /api/admin/subtitles/6/verify',
      ]);
      await waitFor(() => expect(looksRight(dialog)).toHaveLength(1));
    });

    it('Undo after "Use my file instead" says the download plays again, not that a check was taken away', async () => {
      serve();
      const { dialog } = await open();
      await userEvent.click(within(dialog).getByRole('button', { name: /^Use my file instead/ }));
      await userEvent.click(await within(dialog).findByRole('button', { name: /^Undo/ }));

      const notice = await within(dialog).findByText('The download plays again.');
      await waitFor(() => expect(notice).toHaveFocus());
      expect(within(dialog).queryByText('The check is taken away.')).not.toBeInTheDocument();
      expect(
        await within(dialog).findByRole('button', { name: /^Use my file instead/ }),
      ).toBeVisible();
    });

    it('sends one request on a double click', async () => {
      let release;
      const gate = new Promise((resolve) => {
        release = resolve;
      });
      const { requests } = serve({
        answer: (method, url) =>
          url === '/api/admin/subtitles/6/verify' ? gate.then(() => respond(rows()[0])) : null,
      });
      const { dialog, onChanged } = await open();

      await userEvent.dblClick(looksRight(dialog)[0]);
      expect(requests.filter((r) => r.startsWith('POST'))).toEqual([
        'POST /api/admin/subtitles/6/verify',
      ]);
      expect(onChanged).not.toHaveBeenCalled();
      await act(async () => release());

      await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
      expect(requests.filter((r) => r.startsWith('POST'))).toHaveLength(1);
    });

    it('shows the server’s exact words when it is refused, on that file, and does not tell the page', async () => {
      serve({
        answer: (method, url) =>
          url === '/api/admin/subtitles/6/verify'
            ? respond({ error: 'That file cannot be checked' }, 400)
            : null,
      });
      const { dialog, onChanged } = await open();

      await userEvent.click(looksRight(dialog)[0]);

      const alert = await within(dialog).findByRole('alert');
      expect(alert).toHaveTextContent('That file cannot be checked');
      expect(within(within(dialog).getAllByRole('listitem')[0]).getByRole('alert')).toBe(alert);
      expect(onChanged).not.toHaveBeenCalled();
      expect(looksRight(dialog)).toHaveLength(1);
    });
  });

  it('"Use my file instead" posts verify, tells the page, and reads the list again', async () => {
    const { requests } = serve();
    const { dialog, onChanged } = await open();

    await userEvent.click(within(dialog).getByRole('button', { name: /Use my file instead/ }));

    await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
    // The used button leaves with the re-read: focus goes to a status line, not to the page behind the dialog.
    const notice = await within(dialog).findByText('Your file plays instead of the download.');
    await waitFor(() => expect(notice).toHaveFocus());
    expect(requests.filter((r) => r.startsWith('POST'))).toEqual([
      'POST /api/admin/subtitles/7/verify',
    ]);
    await waitFor(() =>
      expect(requests.filter((r) => r === 'GET /api/admin/subtitles').length).toBeGreaterThan(1),
    );
    await waitFor(() =>
      expect(
        within(dialog).queryByRole('button', { name: /Use my file instead/ }),
      ).not.toBeInTheDocument(),
    );
  });

  it('leaves focus where the viewer put it when a tool finishes after they moved on', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    serve({
      answer: (method, url) =>
        url === '/api/admin/subtitles/7/verify' ? gate.then(() => respond(rows()[1])) : null,
    });
    const { dialog, onChanged } = await open();
    await userEvent.click(within(dialog).getByRole('button', { name: /Use my file instead/ }));
    const add = within(dialog).getByRole('button', { name: 'Add my own file…' });
    add.focus();

    await act(async () => release());

    await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
    await within(dialog).findByText('Your file plays instead of the download.');
    expect(add).toHaveFocus();
  });

  it('leaves focus alone when it is outside the dialog (another modal on top) as a tool finishes', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    serve({
      answer: (method, url) =>
        url === '/api/admin/subtitles/7/verify' ? gate.then(() => respond(rows()[1])) : null,
    });
    const { dialog, onChanged } = await open();
    await userEvent.click(within(dialog).getByRole('button', { name: /Use my file instead/ }));
    const add = screen.getByRole('button', { name: 'Open the fixer' });
    add.focus();

    await act(async () => release());

    await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
    await within(dialog).findByText('Your file plays instead of the download.');
    expect(add).toHaveFocus();
  });

  it('"Let CineFilm decide" posts detach and the file leaves the film’s list once it is read again', async () => {
    const { requests } = serve();
    const { dialog, onChanged } = await open();

    await userEvent.click(within(dialog).getByRole('button', { name: /Let CineFilm decide/ }));

    await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
    // The used button leaves with the re-read: focus goes to a status line, not to the page behind the dialog.
    const notice = await within(dialog).findByText('CineFilm decides for that file again.');
    await waitFor(() => expect(notice).toHaveFocus());
    expect(requests.filter((r) => r.startsWith('POST'))).toEqual([
      'POST /api/admin/subtitles/9/detach',
    ]);
    await waitFor(() => expect(within(dialog).getAllByRole('listitem')).toHaveLength(2));
  });

  it('a double click on a tool sends one request', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    const { requests } = serve({
      answer: (method, url) =>
        url === '/api/admin/subtitles/7/verify' ? gate.then(() => respond(rows()[1])) : null,
    });
    const { dialog, onChanged } = await open();
    const button = within(dialog).getByRole('button', { name: /Use my file instead/ });

    await userEvent.dblClick(button);
    expect(requests.filter((r) => r.startsWith('POST'))).toEqual([
      'POST /api/admin/subtitles/7/verify',
    ]);
    expect(onChanged).not.toHaveBeenCalled();
    await act(async () => release());

    await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
    expect(requests.filter((r) => r.startsWith('POST'))).toHaveLength(1);
  });

  it('shows the server’s exact words when a tool is refused, on that file, and does not tell the page', async () => {
    serve({
      answer: (method, url) =>
        url === '/api/admin/subtitles/9/detach'
          ? respond({ error: 'That file was not attached by hand' }, 400)
          : null,
    });
    const { dialog, onChanged } = await open();

    await userEvent.click(within(dialog).getByRole('button', { name: /Let CineFilm decide/ }));

    const alert = await within(dialog).findByRole('alert');
    expect(alert).toHaveTextContent('That file was not attached by hand');
    expect(within(within(dialog).getAllByRole('listitem')[2]).getByRole('alert')).toBe(alert);
    expect(onChanged).not.toHaveBeenCalled();
  });

  describe('Delete this download', () => {
    it('asks first with the safe choice focused, and "Keep it" puts focus back on the button', async () => {
      const { requests } = serve();
      const { dialog } = await open();

      await userEvent.click(within(dialog).getByRole('button', { name: /Delete this download/ }));

      const keep = within(dialog).getByRole('button', { name: 'Keep it' });
      await waitFor(() => expect(keep).toHaveFocus());
      expect(
        within(dialog).getByText(
          "Remove this download? CineFilm won't use this file for this edition again.",
        ),
      ).toBeInTheDocument();
      await userEvent.click(keep);
      const open2 = within(dialog).getByRole('button', { name: /Delete this download/ });
      await waitFor(() => expect(open2).toHaveFocus());
      expect(requests.some((r) => r.startsWith('DELETE'))).toBe(false);
    });

    it('deletes the download, says so, moves focus to that line and the file leaves the list', async () => {
      const { requests } = serve();
      const { dialog, onChanged } = await open();

      await userEvent.click(within(dialog).getByRole('button', { name: /Delete this download/ }));
      await userEvent.click(within(dialog).getByRole('button', { name: 'Delete download' }));

      const notice = await within(dialog).findByText(
        "Download removed. CineFilm won't use it for this edition again.",
      );
      await waitFor(() => expect(notice).toHaveFocus());
      expect(requests.filter((r) => r.startsWith('DELETE'))).toEqual([
        'DELETE /api/admin/subtitles/6',
      ]);
      expect(onChanged).toHaveBeenCalledTimes(1);
      await waitFor(() => expect(within(dialog).getAllByRole('listitem')).toHaveLength(2));
      expect(within(dialog).queryByText('Blade.Runner.1982.1080p')).not.toBeInTheDocument();
    });

    it('shows the server’s refusal and offers "Try again", leaving the file listed', async () => {
      serve({
        answer: (method) =>
          method === 'DELETE'
            ? respond(
                { error: 'Only a subtitle downloaded from OpenSubtitles can be removed' },
                400,
              )
            : null,
      });
      const { dialog, onChanged } = await open();

      await userEvent.click(within(dialog).getByRole('button', { name: /Delete this download/ }));
      await userEvent.click(within(dialog).getByRole('button', { name: 'Delete download' }));

      expect(await within(dialog).findByRole('alert')).toHaveTextContent(
        'Only a subtitle downloaded from OpenSubtitles can be removed',
      );
      expect(within(dialog).getByRole('button', { name: 'Try again' })).toBeInTheDocument();
      expect(within(dialog).getAllByRole('listitem')).toHaveLength(3);
      expect(onChanged).not.toHaveBeenCalled();
    });
  });

  describe('Preview lines', () => {
    it('shows the lines as text, then hides them', async () => {
      serve();
      const { dialog } = await open();

      await userEvent.click(within(dialog).getByRole('button', { name: /^Preview lines: Mine/ }));

      expect(await within(dialog).findByText('<b>Tears</b> in rain')).toBeInTheDocument();
      expect(within(dialog).queryByText('Tears')).not.toBeInTheDocument();
      const hide = within(dialog).getByRole('button', { name: 'Hide lines: Mine.en.srt' });
      expect(hide).toHaveAttribute('aria-expanded', 'true');
      await userEvent.click(hide);
      expect(within(dialog).queryByText('<b>Tears</b> in rain')).not.toBeInTheDocument();
    });
  });

  describe('Add my own file…', () => {
    const own = {
      ...base,
      id: 21,
      kind: 'FULL',
      origin: 'FILE',
      path: 'Blade Runner (1982)/Blade Runner.en.srt',
      filmId: null,
      filmTitle: null,
      state: 'unmatched',
    };

    it('opens the film’s form, attaches through the existing route, tells the page and returns focus', async () => {
      const posts = [];
      const { requests } = serve({
        answer: (method, url, init) => {
          if (url === '/api/admin/subtitles' && method === 'GET') return respond([...rows(), own]);
          if (url === '/api/films/6') {
            return respond({
              id: 6,
              title: 'Blade Runner',
              editions: [{ id: 61, name: 'Original', parts: 1 }],
            });
          }
          if (url === '/api/admin/subtitles/21/attach') {
            posts.push(JSON.parse(init.body));
            return respond(own);
          }
          return null;
        },
      });
      const { dialog, onChanged } = await open();
      const add = within(dialog).getByRole('button', { name: 'Add my own file…' });

      await userEvent.click(add);
      expect(add).toHaveAttribute('aria-expanded', 'true');
      await userEvent.click(
        await within(dialog).findByRole('radio', { name: /Blade Runner\.en\.srt/ }),
      );
      await userEvent.click(within(dialog).getByRole('button', { name: 'Attach' }));

      await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
      expect(posts).toEqual([{ editionId: 61, part: null }]);
      await waitFor(() => expect(add).toHaveFocus());
      expect(within(dialog).queryByRole('form')).not.toBeInTheDocument();
      expect(requests.filter((r) => r.startsWith('POST'))).toEqual([
        'POST /api/admin/subtitles/21/attach',
      ]);
    });

    it('offers the owner’s forced files too, each with its kind and where it sits now, and moves one from another film', async () => {
      const looseForced = {
        ...own,
        id: 22,
        kind: 'FORCED',
        path: 'Loose/Loose.en.forced.srt',
      };
      const onHeat = {
        ...own,
        id: 23,
        kind: 'FORCED',
        path: 'Heat (1995)/Heat.en.forced.srt',
        filmId: 99,
        filmTitle: 'Heat',
        state: 'matched',
      };
      const heatDownload = {
        ...onHeat,
        id: 24,
        origin: 'OPENSUBTITLES',
        path: 'Heat (1995)/.cinefilm/os.srt',
      };
      const posts = [];
      serve({
        answer: (method, url, init) => {
          if (url === '/api/admin/subtitles' && method === 'GET') {
            return respond([...rows(), own, looseForced, onHeat, heatDownload]);
          }
          if (url === '/api/films/6') {
            return respond({
              id: 6,
              title: 'Blade Runner',
              editions: [{ id: 61, name: 'Original', parts: 1 }],
            });
          }
          if (url === '/api/admin/subtitles/23/attach') {
            posts.push(JSON.parse(init.body));
            return respond({ ...onHeat, filmId: 6 });
          }
          return null;
        },
      });
      const { dialog, onChanged } = await open();

      await userEvent.click(within(dialog).getByRole('button', { name: 'Add my own file…' }));

      const form = await within(dialog).findByRole('form');
      expect(
        within(form)
          .getAllByRole('radio')
          .map((r) => r.closest('label').textContent),
      ).toEqual([
        'Blade Runner.en.srt · Full · not attached to a film',
        'Loose.en.forced.srt · Forced · not attached to a film',
        'Heat.en.forced.srt · Forced · on Heat',
      ]);
      await userEvent.click(within(form).getByRole('radio', { name: /Heat\.en\.forced\.srt/ }));
      await userEvent.click(within(form).getByRole('button', { name: 'Attach' }));

      await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
      expect(posts).toEqual([{ editionId: 61, part: null }]);
    });

    it('keeps the form open while an attach is in flight, so only one attach request goes out', async () => {
      let release;
      const gate = new Promise((resolve) => {
        release = resolve;
      });
      const posts = [];
      serve({
        answer: (method, url, init) => {
          if (url === '/api/admin/subtitles' && method === 'GET') return respond([...rows(), own]);
          if (url === '/api/films/6') {
            return respond({
              id: 6,
              title: 'Blade Runner',
              editions: [{ id: 61, name: 'Original', parts: 1 }],
            });
          }
          if (url === '/api/admin/subtitles/21/attach') {
            posts.push(JSON.parse(init.body));
            return gate.then(() => respond(own));
          }
          return null;
        },
      });
      const { dialog, onChanged } = await open();
      const add = within(dialog).getByRole('button', { name: 'Add my own file…' });
      await userEvent.click(add);
      await userEvent.click(
        await within(dialog).findByRole('radio', { name: /Blade Runner\.en\.srt/ }),
      );
      await userEvent.click(within(dialog).getByRole('button', { name: 'Attach' }));

      await userEvent.click(add);

      expect(add).toHaveAttribute('aria-disabled', 'true');
      expect(add).toHaveAttribute('aria-expanded', 'true');
      expect(within(dialog).getByRole('button', { name: 'Attaching…' })).toBeInTheDocument();
      await act(async () => release());
      await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
      expect(posts).toEqual([{ editionId: 61, part: null }]);
      expect(add).not.toHaveAttribute('aria-disabled');
      expect(within(dialog).queryByRole('form')).not.toBeInTheDocument();
    });

    it('"Cancel" closes the form without sending anything', async () => {
      const { requests } = serve();
      const { dialog } = await open();

      await userEvent.click(within(dialog).getByRole('button', { name: 'Add my own file…' }));
      await userEvent.click(await within(dialog).findByRole('button', { name: 'Cancel' }));

      expect(within(dialog).queryByRole('form')).not.toBeInTheDocument();
      expect(requests.some((r) => r.startsWith('POST'))).toBe(false);
    });
  });

  describe('loading and failing', () => {
    it('says so when the film has no file yet', async () => {
      installFetch((method, url) => (url === '/api/admin/subtitles' ? respond([]) : null));
      render(<Host onChanged={vi.fn()} />);
      await userEvent.click(screen.getByRole('button', { name: 'Open the fixer' }));

      expect(
        await screen.findByText('CineFilm has no subtitle file for this film yet.'),
      ).toBeInTheDocument();
    });

    it('shows a skeleton first, then an error with a retry that reads again', async () => {
      let fail = true;
      installFetch((method, url) => {
        if (url !== '/api/admin/subtitles') return null;
        return fail ? respond({ error: 'x' }, 500) : respond(rows());
      });
      render(<Host onChanged={vi.fn()} />);
      await userEvent.click(screen.getByRole('button', { name: 'Open the fixer' }));

      expect(
        await screen.findByText('Couldn’t load this film’s subtitle files.'),
      ).toBeInTheDocument();
      fail = false;
      await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

      expect(
        await screen.findByRole('list', { name: 'Subtitle files for this film' }),
      ).toBeInTheDocument();
    });

    it('keeps the list and says so when a read again fails', async () => {
      let calls = 0;
      installFetch((method, url) => {
        if (url === '/api/admin/subtitles') {
          calls += 1;
          return calls === 1 ? respond(rows()) : respond({ error: 'x' }, 500);
        }
        return url === '/api/admin/subtitles/7/verify' ? respond(rows()[1]) : null;
      });
      const { dialog } = await open();

      await userEvent.click(within(dialog).getByRole('button', { name: /Use my file instead/ }));

      expect(
        await within(dialog).findByText('Couldn’t refresh the subtitle files.'),
      ).toBeInTheDocument();
      expect(within(dialog).getAllByRole('listitem')).toHaveLength(3);
    });
  });

  describe('as a dialog', () => {
    it('puts focus on Close when it opens, closes on Escape and returns focus to the opener', async () => {
      serve();
      const onClosed = vi.fn();
      const { dialog } = await open({ onClosed });
      await waitFor(() =>
        expect(within(dialog).getByRole('button', { name: 'Close' })).toHaveFocus(),
      );

      await userEvent.keyboard('{Escape}');

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(onClosed).toHaveBeenCalledTimes(1);
      await waitFor(() =>
        expect(screen.getByRole('button', { name: 'Open the fixer' })).toHaveFocus(),
      );
    });

    it('keeps Tab inside the dialog', async () => {
      serve();
      const { dialog } = await open();
      const focusable = within(dialog).getAllByRole('button');
      focusable.at(-1).focus();

      await userEvent.tab();

      expect(dialog).toContainElement(document.activeElement);
      expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Close' }));
    });

    it('closing in the middle of a request ignores its late answer, and a late success still tells the page', async () => {
      let release;
      const gate = new Promise((resolve) => {
        release = resolve;
      });
      const { requests } = serve({
        answer: (method, url) =>
          url === '/api/admin/subtitles/7/verify' ? gate.then(() => respond(rows()[1])) : null,
      });
      const { dialog, onChanged } = await open();
      await userEvent.click(within(dialog).getByRole('button', { name: /Use my file instead/ }));

      await userEvent.click(within(dialog).getByRole('button', { name: 'Close' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(onChanged).not.toHaveBeenCalled();
      await act(async () => release());

      await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
      expect(requests.filter((r) => r.startsWith('POST'))).toHaveLength(1);
    });
  });
});
