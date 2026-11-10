import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

const isEditions = (m, url) => m === 'GET' && url === '/api/admin/films/6/editions';
const isSave = (m, url) => m === 'PUT' && url === '/api/admin/films/6/editions';

async function openFromTheRow() {
  renderApp('/admin');
  const attention = await screen.findByRole('region', { name: 'Films needing attention' });
  const button = await within(attention).findByRole('button', { name: 'Edit editions' });
  await userEvent.click(button);
  await screen.findByRole('dialog', { name: 'Blade Runner · 1982' });
  await screen.findByRole('region', { name: 'Edition Extended Edition' });
  return button;
}

describe('Admin: Edit editions', () => {
  it('only the Several files row gets an Edit editions button', async () => {
    installFetch();
    renderApp('/admin');
    const attention = await screen.findByRole('region', { name: 'Films needing attention' });

    expect(await within(attention).findAllByRole('button', { name: 'Edit editions' })).toHaveLength(
      1,
    );
    const item = within(attention).getByText('Blade Runner (1982)');
    expect(item).toBeInTheDocument();
  });

  it('opens the panel for that film, with the suggestion loaded for it', async () => {
    const fetchMock = installFetch();
    await openFromTheRow();

    expect(fetchMock.mock.calls.map((c) => c[0])).toContain('/api/admin/films/6/editions');
    expect(screen.getByText('From the edition tags and part numbers')).toBeInTheDocument();
  });

  it('Cancel closes it and puts focus back on the button, saving nothing', async () => {
    const fetchMock = installFetch();
    const button = await openFromTheRow();

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(button).toHaveFocus();
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false);
  });

  it('saving closes it, says Editions saved, and reads the attention list again so a resolved row goes', async () => {
    let saved = false;
    let attentionAfterSave = 0;
    let reportAfterSave = 0;
    const fetchMock = installFetch((m, url) => {
      if (url === '/api/admin/attention') {
        if (saved) attentionAfterSave += 1;
        return respond(
          saved
            ? { items: fixtures.adminAttention.items.filter((i) => i.kind !== 'multiple-files') }
            : fixtures.adminAttention,
        );
      }
      if (url === '/api/admin/grouping-report' && saved) reportAfterSave += 1;
      if (isSave(m, url)) {
        saved = true;
        return respond({ filmId: 6, newFilmIds: [] });
      }
      return null;
    });
    await openFromTheRow();

    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText('Editions saved')).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Edit editions' })).not.toBeInTheDocument(),
    );
    expect(attentionAfterSave).toBe(1);
    expect(reportAfterSave).toBe(1);
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(1);
  });

  it('a failed editions load inside the panel does not break the page behind it', async () => {
    installFetch((m, url) => (isEditions(m, url) ? respond({ error: 'x' }, 500) : null));
    renderApp('/admin');
    const attention = await screen.findByRole('region', { name: 'Films needing attention' });
    await userEvent.click(await within(attention).findByRole('button', { name: 'Edit editions' }));

    expect(await screen.findByText('Couldn’t load this film’s files.')).toBeInTheDocument();
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Admin' })).toBeInTheDocument();
  });
});
