import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SESSION_EXPIRED_EVENT } from '../api/client.js';
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

  it('leaves the keyboard to the sign-in card when the session expires over it', async () => {
    installFetch();
    const onEscape = vi.fn();
    window.addEventListener('keydown', onEscape);
    await openFromTheRow();

    act(() => {
      window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT));
    });
    const password = await screen.findByLabelText('Password');
    await waitFor(() => expect(password).toHaveFocus());
    await userEvent.type(password, 'secret');
    await userEvent.tab();
    expect(screen.getByRole('button', { name: 'Sign in' })).toHaveFocus();
    await userEvent.keyboard('{Escape}');

    expect(screen.getByRole('dialog', { name: 'Blade Runner · 1982' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign in' })).toHaveFocus();
    window.removeEventListener('keydown', onEscape);
  });

  it('a save that finishes after its dialog closed refreshes the lists but leaves the next dialog alone', async () => {
    let release;
    let attentionCalls = 0;
    const second = { ...fixtures.adminAttention.items[3], filmId: 7, title: 'Alien', year: 1979 };
    const fetchMock = installFetch((m, url) => {
      if (url === '/api/admin/attention') {
        attentionCalls += 1;
        return respond({ items: [fixtures.adminAttention.items[3], second] });
      }
      if (isSave(m, url)) {
        return new Promise((resolve) => {
          release = () => resolve(respond({ filmId: 6, newFilmIds: [] }));
        });
      }
      return null;
    });
    renderApp('/admin');
    const attention = await screen.findByRole('region', { name: 'Films needing attention' });
    const buttons = await within(attention).findAllByRole('button', { name: 'Edit editions' });
    await userEvent.click(buttons[0]);
    await screen.findByRole('region', { name: 'Edition Extended Edition' });
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByRole('button', { name: 'Saving…' });
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    await userEvent.click(within(attention).getAllByRole('button', { name: 'Edit editions' })[1]);
    await screen.findByRole('dialog', { name: 'Alien · 1979' });
    await screen.findByRole('region', { name: 'Edition Extended Edition' });
    await userEvent.selectOptions(screen.getAllByRole('combobox', { name: 'Part' })[1], 'Part 3');
    const before = attentionCalls;

    await act(async () => release());

    await waitFor(() => expect(attentionCalls).toBeGreaterThan(before));
    expect(screen.getByRole('dialog', { name: 'Alien · 1979' })).toBeInTheDocument();
    expect(screen.getAllByRole('combobox', { name: 'Part' })[1]).toHaveValue('3');
    expect(screen.queryByText('Editions saved')).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(1);
  });
});
