import { useState } from 'react';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import SubtitlePicker from './SubtitlePicker.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const deferred = () => {
  let resolve;
  const promise = new Promise((res) => {
    resolve = res;
  });
  return { promise, resolve };
};

const FILM = { id: 2, title: 'Taken', year: 2008 };
const isSearch = (m, url) =>
  m === 'GET' && url.startsWith('/api/admin/editions/101/opensubtitles?');
const isDownload = (m, url) =>
  m === 'POST' && url === '/api/admin/editions/101/opensubtitles/download';
const downloads = (fetchMock) => fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST');
const answer = (fileId = 5012) => ({
  editionId: 101,
  kind: 'forced',
  fileId,
  release: 'Heat.1995.Directors.Cut.BluRay.x264',
  downloadsLeft: 2,
});

/** The picker, opened from a real button so focus has somewhere to come back to. */
function Harness({ onPicked = () => {}, kind = 'forced', film = FILM, editionName = null }) {
  const [open, setOpen] = useState(false);
  const [opener, setOpener] = useState(null);
  return (
    <>
      <button
        type="button"
        onClick={(event) => {
          setOpener(event.currentTarget);
          setOpen(true);
        }}
      >
        Find forced subtitles…
      </button>
      {open ? (
        <SubtitlePicker
          film={film}
          editionId={101}
          editionName={editionName}
          kind={kind}
          opener={opener}
          onClose={() => setOpen(false)}
          onPicked={(done) => {
            setOpen(false);
            onPicked(done);
          }}
        />
      ) : null}
    </>
  );
}

async function open(props, route) {
  const fetchMock = installFetch(route);
  const user = userEvent.setup();
  const onPicked = vi.fn();
  render(<Harness onPicked={onPicked} {...props} />);
  const opener = screen.getByRole('button', { name: 'Find forced subtitles…' });
  await user.click(opener);
  return { user, onPicked, opener, fetchMock };
}

const dialog = () => screen.getByRole('dialog', { name: 'Taken (2008)' });
const choose = (name) => screen.getByRole('button', { name: `Use this file: ${name}` });

describe('SubtitlePicker: opening and searching', () => {
  it('is a modal dialog named for the film, with the kind in its eyebrow and focus on Close while it searches', async () => {
    const held = deferred();
    await open({}, (m, url) => (isSearch(m, url) ? held.promise : null));

    expect(dialog()).toHaveAttribute('aria-modal', 'true');
    expect(within(dialog()).getByText('Forced subtitles from OpenSubtitles')).toBeInTheDocument();
    expect(
      within(dialog()).getByText(/^Only files that hold the foreign lines/),
    ).toBeInTheDocument();
    expect(screen.getByRole('status', { name: 'Searching OpenSubtitles…' })).toHaveAttribute(
      'aria-busy',
      'true',
    );
    expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus();
    expect(screen.queryByRole('list', { name: 'OpenSubtitles results' })).not.toBeInTheDocument();
  });

  it('says which cut it searches when the film has several, and says nothing of it otherwise', async () => {
    await open({ editionName: 'Director’s Cut' });

    expect(
      await screen.findByRole('dialog', { name: 'Taken (2008) · Director’s Cut' }),
    ).toBeVisible();
  });

  it('puts no cut in the title of a film with a single edition', async () => {
    await open();

    expect(await screen.findByRole('dialog', { name: 'Taken (2008)' })).toBeVisible();
    expect(screen.getByRole('dialog')).not.toHaveAccessibleName(/ · /);
  });

  it('words a full search for English subtitles and leaves the year out for a film with none', async () => {
    await open({ kind: 'full', film: { id: 2, title: 'Taken', year: null } });

    expect(await screen.findByRole('dialog', { name: 'Taken' })).toBeInTheDocument();
    expect(screen.getByText('English subtitles from OpenSubtitles')).toBeInTheDocument();
    expect(screen.getByText(/^Best matches for this cut come first/)).toBeInTheDocument();
  });

  it('lists the results in the server’s order with the facts that help choose, and a choose button each', async () => {
    const { fetchMock } = await open();

    const list = await screen.findByRole('list', { name: 'OpenSubtitles results' });

    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/editions/101/opensubtitles?kind=forced');
    const rows = within(list).getAllByRole('listitem');
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent(
      'Heat.1995.Directors.Cut.BluRay.x264' +
        '4,210 downloads · 23.976 fps · Trusted uploader · Uploaded 2019',
    );
    expect(rows[1]).toHaveTextContent(
      'Heat.1995.1080p.BluRay380 downloads · 25 fps · Uploaded 2016',
    );
    expect(rows[2]).toHaveTextContent('Heat.1995.HDTV12 downloads · Hearing impaired');
    expect(within(rows[0]).getByRole('button', { name: /^Use this file:/ })).toHaveTextContent(
      'Use this file',
    );
    expect(screen.getByText('3 more files can be added for this edition')).toBeInTheDocument();
  });

  it('says plainly when OpenSubtitles has nothing', async () => {
    const none = { ...structuredClone(fixtures.adminOpenSubtitles.forced), results: [] };
    await open({}, (m, url) => (isSearch(m, url) ? respond(none) : null));

    expect(await screen.findByText('OpenSubtitles has no files for this film yet.')).toBeVisible();
    expect(screen.queryByRole('list', { name: 'OpenSubtitles results' })).not.toBeInTheDocument();
  });

  it('shows a failed search in the server’s words, in the error banner, and searches again on Try again', async () => {
    const answers = [
      respond(
        {
          error:
            'This film has no IMDb id, so OpenSubtitles cannot be searched. Fix its match first.',
        },
        400,
      ),
    ];
    const { user } = await open({}, (m, url) => (isSearch(m, url) ? answers.shift() : null));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(
      'This film has no IMDb id, so OpenSubtitles cannot be searched. Fix its match first.',
    );
    expect(alert).toHaveClass('ee__banner');

    await user.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByRole('list', { name: 'OpenSubtitles results' })).toBeVisible();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('SubtitlePicker: choosing a file', () => {
  it('posts the choice, then calls onPicked, closes and returns focus to the opener', async () => {
    const { user, onPicked, opener, fetchMock } = await open();
    await screen.findByRole('list', { name: 'OpenSubtitles results' });

    await user.click(choose('Heat.1995.1080p.BluRay'));

    expect(downloads(fetchMock)).toHaveLength(1);
    expect(JSON.parse(downloads(fetchMock)[0][1].body)).toEqual({ fileId: 5011, kind: 'forced' });
    expect(onPicked).toHaveBeenCalledTimes(1);
    expect(onPicked.mock.calls[0][0]).toMatchObject({ editionId: 101, fileId: 5011 });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });

  it('sends one POST for a second click while a download is running, and marks the busy button', async () => {
    const held = deferred();
    const { user, fetchMock, onPicked } = await open({}, (m, url) =>
      isDownload(m, url) ? held.promise : null,
    );
    await screen.findByRole('list', { name: 'OpenSubtitles results' });

    await user.click(choose('Heat.1995.Directors.Cut.BluRay.x264'));
    await user.click(choose('Heat.1995.Directors.Cut.BluRay.x264'));
    await user.click(choose('Heat.1995.HDTV'));

    expect(downloads(fetchMock)).toHaveLength(1);
    const busy = choose('Heat.1995.Directors.Cut.BluRay.x264');
    expect(busy).toHaveTextContent('Downloading…');
    expect(busy).toHaveAttribute('aria-busy', 'true');
    expect(busy).toHaveAttribute('aria-disabled', 'true');
    expect(choose('Heat.1995.HDTV')).toHaveAttribute('aria-disabled', 'true');
    expect(choose('Heat.1995.HDTV')).not.toHaveAttribute('aria-busy');
    expect(onPicked).not.toHaveBeenCalled();

    await act(async () => held.resolve(respond(answer())));
    expect(onPicked).toHaveBeenCalledTimes(1);
  });

  it.each([
    [400, 'That file was not in a recent search for this edition. Search again.'],
    [409, 'A scan is running'],
    [422, 'That subtitle file could not be read.'],
    [429, 'OpenSubtitles daily limit reached — try again tomorrow.'],
    [
      502,
      "OpenSubtitles counted this download against today's limit but could not deliver the file. Try again.",
    ],
    [503, 'OpenSubtitles is not answering. Try again in a few minutes.'],
  ])(
    'shows the server’s exact words for a %i and lets another file be chosen',
    async (status, text) => {
      const answers = [respond({ error: text }, status)];
      const { user, onPicked, fetchMock } = await open({}, (m, url) =>
        isDownload(m, url) ? answers.shift() : null,
      );
      await screen.findByRole('list', { name: 'OpenSubtitles results' });

      await user.click(choose('Heat.1995.Directors.Cut.BluRay.x264'));

      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent(text);
      expect(alert).toHaveClass('ee__banner');
      expect(onPicked).not.toHaveBeenCalled();
      expect(dialog()).toBeInTheDocument();
      expect(choose('Heat.1995.Directors.Cut.BluRay.x264')).toHaveTextContent('Use this file');
      expect(choose('Heat.1995.Directors.Cut.BluRay.x264')).not.toHaveAttribute('aria-disabled');

      await user.click(choose('Heat.1995.1080p.BluRay'));

      expect(downloads(fetchMock)).toHaveLength(2);
      expect(onPicked).toHaveBeenCalledTimes(1);
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    },
  );
});

describe('SubtitlePicker: closing', () => {
  it.each([
    ['Escape', async (user) => user.keyboard('{Escape}')],
    ['Cancel', async (user) => user.click(screen.getByRole('button', { name: 'Cancel' }))],
    ['Close', async (user) => user.click(screen.getByRole('button', { name: 'Close' }))],
  ])('%s closes it and returns focus to the opener', async (_name, close) => {
    const { user, opener, onPicked } = await open();
    await screen.findByRole('list', { name: 'OpenSubtitles results' });

    await close(user);

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
    expect(onPicked).not.toHaveBeenCalled();
  });

  it('keeps Tab inside the dialog', async () => {
    const { user } = await open();
    await screen.findByRole('list', { name: 'OpenSubtitles results' });

    await user.tab({ shift: true });
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus();
  });

  it('tells the layout a download that was still running when it was closed was saved, once', async () => {
    const held = deferred();
    const { user, onPicked, opener } = await open({}, (m, url) =>
      isDownload(m, url) ? held.promise : null,
    );
    await screen.findByRole('list', { name: 'OpenSubtitles results' });
    await user.click(choose('Heat.1995.HDTV'));

    await user.keyboard('{Escape}');
    await act(async () => held.resolve(respond(answer(5013))));

    // The picker is gone; the harness's own onPicked (the layout's) still hears the file was saved.
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(onPicked).toHaveBeenCalledTimes(1);
    expect(opener).toHaveFocus();
  });

  it('reports a file that arrives while the fade-out runs, which the end of the fade must not lose', async () => {
    const held = deferred();
    const { onPicked } = await open({}, (m, url) => (isDownload(m, url) ? held.promise : null));
    await screen.findByRole('list', { name: 'OpenSubtitles results' });
    vi.stubGlobal('matchMedia', () => ({ matches: false }));
    vi.useFakeTimers();
    try {
      // userEvent waits on timers of its own, which a frozen clock does not give it: a native click and a keydown.
      await act(async () => choose('Heat.1995.HDTV').click());
      act(() => {
        fireEvent.keyDown(document.activeElement, { key: 'Escape' });
      });
      // The fade has begun (the dialog is still there) and the answer comes inside it.
      expect(screen.getByRole('dialog')).toBeInTheDocument();
      await act(async () => held.resolve(respond(answer(5013))));
      await act(async () => vi.advanceTimersByTime(200));

      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(onPicked).toHaveBeenCalledTimes(1);
      expect(onPicked.mock.calls[0][0]).toMatchObject({ fileId: 5013 });
    } finally {
      vi.useRealTimers();
    }
  });
});
