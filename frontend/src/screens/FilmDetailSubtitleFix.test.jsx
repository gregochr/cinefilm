import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

// A downloaded forced subtitle of Heat (film 1, edition 11), played now.
const download = {
  ...fixtures.adminSubtitles[5],
  id: 70,
  filmId: 1,
  filmTitle: 'Heat',
  editionId: 11,
  kind: 'FORCED',
  origin: 'OPENSUBTITLES',
  release: 'Heat.1995.1080p',
  served: true,
};

function serve(mutate = () => {}) {
  const film = structuredClone(fixtures.heat);
  mutate(film);
  const list = [download];
  const requests = [];
  installFetch((method, url) => {
    requests.push(`${method} ${url}`);
    if (url === '/api/films/1' && method === 'GET') return respond(film);
    if (url === '/api/admin/subtitles' && method === 'GET') return respond(structuredClone(list));
    if (url === '/api/admin/subtitles/70' && method === 'DELETE') {
      list.splice(0, 1);
      film.editions[0].forcedSubtitle = null;
      return respond(null, 204);
    }
    return null;
  });
  return requests;
}

const fixButton = () => screen.findByRole('button', { name: 'Fix subtitles…' });

describe('Film detail: Subtitles → Fix…', () => {
  it('has a quiet "Fix…" entry beside the Subtitles field of a film that has subtitles', async () => {
    serve();
    renderApp('/films/1');

    const fix = await fixButton();

    expect(fix).toHaveTextContent('Fix…');
    expect(fix).toHaveAttribute('aria-haspopup', 'dialog');
    expect(screen.getByRole('combobox', { name: 'Subtitles' })).toBeInTheDocument();
  });

  it('has it too for a film with no subtitles, where "Add my own file…" is the way in', async () => {
    serve((film) => {
      film.editions[0].subtitleStreams = [];
      film.editions[0].forcedSubtitle = null;
      film.editions[0].fullSubtitle = null;
    });
    renderApp('/films/1');

    expect(await fixButton()).toBeInTheDocument();
    expect(screen.getByText('None for this film')).toBeInTheDocument();
  });

  it('opens the film’s files in a dialog and puts focus back on "Fix…" when it closes', async () => {
    serve();
    renderApp('/films/1');
    const fix = await fixButton();

    await userEvent.click(fix);

    const dialog = await screen.findByRole('dialog', { name: 'Heat (1995)' });
    expect(await within(dialog).findByText('Heat.1995.1080p')).toBeInTheDocument();
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(fix).toHaveFocus());
  });

  it('reads the film again after a change, so the subtitle choices follow the files', async () => {
    const requests = serve((film) => {
      film.editions[0].forcedSubtitle = { source: 'DOWNLOAD', ownerVerified: false, version: 1 };
    });
    renderApp('/films/1');
    await userEvent.click(await fixButton());
    const dialog = await screen.findByRole('dialog');
    const filmReads = () => requests.filter((r) => r === 'GET /api/films/1').length;
    await within(dialog).findByText('Heat.1995.1080p');
    const before = filmReads();

    await userEvent.click(within(dialog).getByRole('button', { name: /Delete this download/ }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete download' }));

    await waitFor(() => expect(filmReads()).toBeGreaterThan(before));
    await waitFor(() =>
      expect(within(dialog).queryByText('Heat.1995.1080p')).not.toBeInTheDocument(),
    );
  });
});
