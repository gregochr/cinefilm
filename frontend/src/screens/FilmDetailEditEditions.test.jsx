import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FADE_MS } from '../components/useDialog.js';
import { installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

async function openFromFilmDetail() {
  renderApp('/films/1');
  const button = await screen.findByRole('button', { name: 'Edit editions…' });
  await userEvent.click(button);
  await screen.findByRole('dialog', { name: 'Heat · 1995' });
  await screen.findByRole('region', { name: 'Edition Extended Edition' });
  return button;
}

describe('Film detail: Edit editions…', () => {
  it('opens the panel for this film', async () => {
    const fetchMock = installFetch();
    await openFromFilmDetail();

    expect(fetchMock.mock.calls.map((c) => c[0])).toContain('/api/admin/films/1/editions');
  });

  it('closing it returns focus to the button', async () => {
    installFetch();
    const button = await openFromFilmDetail();

    await userEvent.keyboard('{Escape}');

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(button).toHaveFocus();
  });

  it('saving closes it, says Editions saved and reads the film again', async () => {
    let filmCalls = 0;
    installFetch((m, url) => {
      if (url === '/api/films/1') filmCalls += 1;
      if (m === 'PUT' && url === '/api/admin/films/1/editions') {
        return respond({ filmId: 1, newFilmIds: [] });
      }
      return null;
    });
    await openFromFilmDetail();
    const before = filmCalls;

    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText('Editions saved')).toBeInTheDocument();
    await waitFor(() => expect(filmCalls).toBe(before + 1));
  });

  it('forgets a local watched mark once the arrangement is saved, so the server’s resume position shows', async () => {
    installFetch((m, url) =>
      m === 'PUT' && url === '/api/admin/films/1/editions'
        ? respond({ filmId: 1, newFilmIds: [] })
        : null,
    );
    renderApp('/films/1');
    await userEvent.click(await screen.findByRole('button', { name: 'Mark as watched' }));
    expect(screen.queryByRole('link', { name: /^Resume/ })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Edit editions…' }));
    await screen.findByRole('region', { name: 'Edition Extended Edition' });

    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByRole('link', { name: /^Resume/ })).toBeInTheDocument();
  });
});

describe('Film detail: Edit editions… cancelled while it saves', () => {
  // The fade is held on a fake clock, so "during the fade" does not depend on real time passing.
  async function cancelledThenSaved() {
    vi.stubGlobal('matchMedia', () => ({ matches: false }));
    const film = { reads: 0 };
    let release;
    installFetch((m, url) => {
      if (url === '/api/films/1') film.reads += 1;
      if (m === 'PUT' && url === '/api/admin/films/1/editions') {
        return new Promise((resolve) => {
          release = () => resolve(respond({ filmId: 1, newFilmIds: [] }));
        });
      }
      return null;
    });
    await openFromFilmDetail();
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByRole('button', { name: 'Saving…' });
    film.before = film.reads;
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    vi.stubGlobal('jest', { advanceTimersByTime: (ms) => vi.advanceTimersByTime(ms) });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });

    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    await act(async () => release());
    return film;
  }

  it('a save that lands during the fade reads the film once', async () => {
    const film = await cancelledThenSaved();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    await vi.waitFor(() => expect(film.reads).toBe(film.before + 1));

    await act(async () => vi.advanceTimersByTimeAsync(FADE_MS));
    await act(async () => {}); // let anything still pending land before the exact count

    expect(film.reads).toBe(film.before + 1);
  });

  it('a save that lands during the fade says nothing, because the person cancelled', async () => {
    await cancelledThenSaved();
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    await act(async () => vi.advanceTimersByTimeAsync(FADE_MS));

    expect(screen.queryByText('Editions saved')).not.toBeInTheDocument();
  });

  it('the dialog still closes when its fade ends', async () => {
    await cancelledThenSaved();
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    await act(async () => vi.advanceTimersByTimeAsync(FADE_MS));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
