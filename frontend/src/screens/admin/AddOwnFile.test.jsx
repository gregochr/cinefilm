import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../../test/mockApi.jsx';
import AddOwnFile from './AddOwnFile.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

const file = (over = {}) => ({
  ...structuredClone(fixtures.adminSubtitles[0]),
  id: 21,
  kind: 'FULL',
  path: 'Rashomon (1950)/Rashomon.en.srt',
  filmId: null,
  filmTitle: null,
  state: 'unmatched',
  ...over,
});
const edition = (id, name, parts = 1) => ({ id, name, parts });

/** The routes the form uses: the file list, the film with its editions, and the attach POST (recorded). */
function serve({
  files = [file()],
  editions = [edition(60, 'Original')],
  attach = () => null,
} = {}) {
  const attaches = [];
  const fetchMock = installFetch((method, url, init) => {
    if (url === '/api/admin/subtitles') return respond(files);
    if (url === '/api/films/6') return respond({ id: 6, title: 'Rashomon', editions });
    const match = url.match(/^\/api\/admin\/subtitles\/(\d+)\/attach$/);
    if (match && method === 'POST') {
      attaches.push({ id: Number(match[1]), body: JSON.parse(init.body) });
      return attach() ?? respond(files[0]);
    }
    return null;
  });
  return { attaches, fetchMock };
}

function renderForm(props = {}) {
  const handlers = { onAttached: vi.fn(), onCancel: vi.fn() };
  render(<AddOwnFile filmId={6} title="Rashomon (1950)" {...handlers} {...props} />);
  return handlers;
}

describe('AddOwnFile', () => {
  it('lists the owner’s full subtitle files, and "Attach" waits until a file is chosen', async () => {
    serve({ files: [file(), file({ id: 22, path: 'x/Forced.en.forced.srt', kind: 'FORCED' })] });
    renderForm();

    expect(screen.getByText('Looking for subtitle files…')).toBeInTheDocument();
    expect(await screen.findByRole('radio', { name: /Rashomon\.en\.srt/ })).not.toBeChecked();
    expect(screen.getByText('· not attached to a film')).toBeInTheDocument();
    expect(screen.queryByRole('radio', { name: /Forced/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Attach' })).toHaveAttribute('aria-disabled', 'true');
  });

  it('attaches the chosen file to the film’s only edition and tells the row, with no edition question', async () => {
    const { attaches } = serve();
    const { onAttached } = renderForm();

    await userEvent.click(await screen.findByRole('radio', { name: /Rashomon\.en\.srt/ }));
    expect(screen.queryByRole('group', { name: 'Edition' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Attach' }));

    await waitFor(() => expect(onAttached).toHaveBeenCalledTimes(1));
    expect(attaches).toEqual([{ id: 21, body: { editionId: 60, part: null } }]);
  });

  it('asks which edition when there are several, and which part for a split edition', async () => {
    const { attaches } = serve({
      editions: [edition(60, 'Theatrical Cut'), edition(61, 'Director’s Cut', 2)],
    });
    const { onAttached } = renderForm();

    await userEvent.click(await screen.findByRole('radio', { name: /Rashomon\.en\.srt/ }));
    // No edition chosen yet: Attach stays inert and sends nothing.
    await userEvent.click(screen.getByRole('button', { name: 'Attach' }));
    expect(attaches).toEqual([]);

    await userEvent.click(await screen.findByRole('radio', { name: 'Director’s Cut · 2 files' }));
    expect(screen.getByRole('radio', { name: 'Whole film' })).toBeChecked();
    await userEvent.click(screen.getByRole('radio', { name: 'Part 2' }));
    await userEvent.click(screen.getByRole('button', { name: 'Attach' }));

    await waitFor(() => expect(onAttached).toHaveBeenCalledTimes(1));
    expect(attaches).toEqual([{ id: 21, body: { editionId: 61, part: 2 } }]);
  });

  it('says so when no file is waiting, and offers no Attach target', async () => {
    serve({ files: [] });
    renderForm();

    expect(
      await screen.findByText(/No full subtitle files are waiting\. Put the file beside the film/),
    ).toBeInTheDocument();
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
  });

  it('names where a file that sits on another film is now', async () => {
    serve({ files: [file({ filmId: 9, filmTitle: 'Ran' })] });
    renderForm();

    expect(await screen.findByText('· on Ran')).toBeInTheDocument();
  });

  it('keeps the form and says what the server said when attaching is refused', async () => {
    serve({ attach: () => respond({ error: 'That edition has no such part' }, 400) });
    const { onAttached } = renderForm();
    await userEvent.click(await screen.findByRole('radio', { name: /Rashomon\.en\.srt/ }));

    await userEvent.click(screen.getByRole('button', { name: 'Attach' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('That edition has no such part');
    expect(onAttached).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Attach' })).not.toHaveAttribute('aria-disabled');
  });

  it('sends once when Attach is clicked twice while the first is in flight', async () => {
    let release;
    const held = new Promise((resolve) => {
      release = resolve;
    });
    const { attaches } = serve({ attach: () => held.then(() => respond(file())) });
    const { onAttached } = renderForm();
    await userEvent.click(await screen.findByRole('radio', { name: /Rashomon\.en\.srt/ }));
    const attach = screen.getByRole('button', { name: 'Attach' });

    await userEvent.click(attach);
    await screen.findByRole('button', { name: 'Attaching…' });
    await userEvent.click(screen.getByRole('button', { name: 'Attaching…' }));
    expect(attaches).toHaveLength(1);

    release();
    await waitFor(() => expect(onAttached).toHaveBeenCalledTimes(1));
    expect(attaches).toHaveLength(1);
  });

  it('shows a banner with Try again when the files cannot be loaded', async () => {
    let failing = true;
    installFetch((method, url) => {
      if (url === '/api/admin/subtitles') return failing ? respond(null, 500) : respond([file()]);
      if (url === '/api/films/6') return respond({ id: 6, editions: [edition(60, 'Original')] });
      return null;
    });
    renderForm();

    expect(await screen.findByRole('alert')).toHaveTextContent('Couldn’t load the subtitle files.');
    failing = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByRole('radio', { name: /Rashomon\.en\.srt/ })).toBeInTheDocument();
  });

  it('shows a banner with Try again when the film’s editions cannot be loaded', async () => {
    let failing = true;
    installFetch((method, url) => {
      if (url === '/api/admin/subtitles') return respond([file()]);
      if (url === '/api/films/6') {
        return failing
          ? respond(null, 500)
          : respond({ id: 6, editions: [edition(60, 'Original')] });
      }
      return null;
    });
    renderForm();

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Couldn’t load that film’s editions.',
    );
    failing = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    await userEvent.click(await screen.findByRole('radio', { name: /Rashomon\.en\.srt/ }));
    expect(screen.getByRole('button', { name: 'Attach' })).not.toHaveAttribute('aria-disabled');
  });

  it('Cancel calls onCancel and sends nothing', async () => {
    const { attaches } = serve();
    const { onCancel } = renderForm();
    await screen.findByRole('radio', { name: /Rashomon\.en\.srt/ });

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(attaches).toEqual([]);
  });

  it('says the editions are loading while they load', async () => {
    installFetch((method, url) => {
      if (url === '/api/admin/subtitles') return respond([file()]);
      if (url === '/api/films/6') return new Promise(() => {});
      return null;
    });
    renderForm();

    expect(await screen.findByText('Loading that film’s editions…')).toBeInTheDocument();
    expect(
      screen.queryByText(/no edition a subtitle file can be added to/),
    ).not.toBeInTheDocument();
  });

  it('says so when the film has no edition a file can be added to', async () => {
    serve({ editions: [] });
    renderForm();

    expect(
      await screen.findByText('This film has no edition a subtitle file can be added to.'),
    ).toBeInTheDocument();
    expect(screen.queryByText('Loading that film’s editions…')).not.toBeInTheDocument();
  });

  it('does not say "no edition" when the editions could not be read', async () => {
    installFetch((method, url) => {
      if (url === '/api/admin/subtitles') return respond([file()]);
      if (url === '/api/films/6') return respond(null, 500);
      return null;
    });
    renderForm();

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Couldn’t load that film’s editions.',
    );
    expect(
      screen.queryByText(/no edition a subtitle file can be added to/),
    ).not.toBeInTheDocument();
  });

  it('cannot be cancelled while the attach is in flight, so it cannot be reopened and sent twice', async () => {
    let release;
    const held = new Promise((resolve) => {
      release = resolve;
    });
    const { attaches } = serve({ attach: () => held.then(() => respond(file())) });
    const { onAttached, onCancel } = renderForm();
    await userEvent.click(await screen.findByRole('radio', { name: /Rashomon\.en\.srt/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Attach' }));
    const cancel = screen.getByRole('button', { name: 'Cancel' });
    expect(cancel).toHaveAttribute('aria-disabled', 'true');

    await userEvent.click(cancel);
    expect(onCancel).not.toHaveBeenCalled();

    release();
    await waitFor(() => expect(onAttached).toHaveBeenCalledTimes(1));
    expect(attaches).toHaveLength(1);
    expect(cancel).not.toHaveAttribute('aria-disabled');
  });
});
