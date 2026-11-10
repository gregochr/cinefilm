import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
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
