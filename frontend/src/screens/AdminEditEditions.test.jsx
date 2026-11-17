import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SESSION_EXPIRED_EVENT } from '../api/client.js';
import { FADE_MS } from '../components/useDialog.js';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const isEditions = (m, url) => m === 'GET' && url === '/api/admin/films/6/editions';
const isSave = (m, url) => m === 'PUT' && url === '/api/admin/films/6/editions';

async function openFromTheRow() {
  renderApp('/admin');
  const attention = await screen.findByRole('region', { name: 'Sort out several files' });
  const button = (
    await within(attention).findAllByRole('button', { name: 'Sort out editions…' })
  )[0];
  await userEvent.click(button);
  await screen.findByRole('dialog', { name: 'Blade Runner · 1982' });
  await screen.findByRole('region', { name: 'Edition Extended Edition' });
  return button;
}

describe('Admin: Edit editions', () => {
  it('each Sort out several files row (several files, parts that do not match) gets an Edit editions button', async () => {
    installFetch();
    renderApp('/admin');
    const group = await screen.findByRole('region', { name: 'Sort out several files' });

    const buttons = await within(group).findAllByRole('button', { name: 'Sort out editions…' });
    expect(buttons).toHaveLength(2);
    expect(buttons[0]).toHaveAccessibleDescription(
      expect.stringContaining('Choose which file is which cut, the part order and the default.'),
    );
    expect(within(group).getByText('Blade Runner')).toBeInTheDocument();
    expect(within(group).getByText('The Two Towers')).toBeInTheDocument();
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
    const fetchMock = installFetch((m, url) => {
      if (url === '/api/admin/attention') {
        if (saved) attentionAfterSave += 1;
        return respond(
          saved
            ? {
                items: fixtures.adminAttention.items.filter(
                  (i) => !['multiple-files', 'parts-mismatch'].includes(i.kind),
                ),
              }
            : fixtures.adminAttention,
        );
      }
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
      expect(screen.queryByRole('button', { name: 'Sort out editions…' })).not.toBeInTheDocument(),
    );
    expect(attentionAfterSave).toBe(1);
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(1);
  });

  it('saving puts focus on the To do heading when the row that opened it goes', async () => {
    let saved = false;
    installFetch((m, url) => {
      if (url === '/api/admin/attention') {
        return respond(
          saved
            ? {
                items: fixtures.adminAttention.items.filter(
                  (i) => !['multiple-files', 'parts-mismatch'].includes(i.kind),
                ),
              }
            : fixtures.adminAttention,
        );
      }
      if (isSave(m, url)) {
        saved = true;
        return respond({ filmId: 6, newFilmIds: [] });
      }
      return null;
    });
    await openFromTheRow();

    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Sort out editions…' })).not.toBeInTheDocument(),
    );
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: 'To do' })).toHaveFocus(),
    );
  });

  it('a failed editions load inside the panel does not break the page behind it', async () => {
    installFetch((m, url) => (isEditions(m, url) ? respond({ error: 'x' }, 500) : null));
    renderApp('/admin');
    const attention = await screen.findByRole('region', { name: 'Sort out several files' });
    await userEvent.click(
      (await within(attention).findAllByRole('button', { name: 'Sort out editions…' }))[0],
    );

    expect(await screen.findByText('Couldn’t load this film’s files.')).toBeInTheDocument();
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'To do' })).toBeInTheDocument();
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
    const attention = await screen.findByRole('region', { name: 'Sort out several files' });
    const buttons = await within(attention).findAllByRole('button', { name: 'Sort out editions…' });
    await userEvent.click(buttons[0]);
    await screen.findByRole('region', { name: 'Edition Extended Edition' });
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByRole('button', { name: 'Saving…' });
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    await userEvent.click(
      within(attention).getAllByRole('button', { name: 'Sort out editions…' })[1],
    );
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

describe('Admin: Edit editions, cancelled while it saves', () => {
  // The fade is held on a fake clock, so "during the fade" does not depend on real time passing.
  function holdClock() {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    vi.stubGlobal('jest', { advanceTimersByTime: (ms) => vi.advanceTimersByTime(ms) });
    return userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
  }
  const heading = () => screen.getByRole('heading', { level: 1, name: 'To do' });
  const sortOut = () => screen.queryAllByRole('button', { name: 'Sort out editions…' });

  // The save is held; once released the re-read answers without the rows the dialog was opened from.
  function holdSave() {
    const held = { saved: false, attention: 0 };
    installFetch((m, url) => {
      if (url === '/api/admin/attention') {
        held.attention += 1;
        return respond(
          held.saved
            ? {
                items: fixtures.adminAttention.items.filter(
                  (i) => !['multiple-files', 'parts-mismatch'].includes(i.kind),
                ),
              }
            : fixtures.adminAttention,
        );
      }
      if (isSave(m, url)) {
        return new Promise((resolve) => {
          held.release = () => {
            held.saved = true;
            resolve(respond({ filmId: 6, newFilmIds: [] }));
          };
        });
      }
      return null;
    });
    return held;
  }

  it('a save that lands during the fade still reads the lists again, once, with no toast', async () => {
    vi.stubGlobal('matchMedia', () => ({ matches: false }));
    const held = holdSave();
    await openFromTheRow();
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByRole('button', { name: 'Saving…' });
    const before = held.attention;
    const user = holdClock();

    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    await act(async () => held.release());
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    await vi.waitFor(() => expect(held.attention).toBe(before + 1));
    await act(async () => vi.advanceTimersByTimeAsync(FADE_MS));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await act(async () => {}); // let anything still pending land before the exact count
    expect(held.attention).toBe(before + 1);
    expect(screen.queryByText('Editions saved')).not.toBeInTheDocument();
  });

  it('keeps focus off the heading until the dialog is gone, then puts it there', async () => {
    vi.stubGlobal('matchMedia', () => ({ matches: false }));
    const held = holdSave();
    await openFromTheRow();
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByRole('button', { name: 'Saving…' });
    const user = holdClock();

    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    await act(async () => held.release());
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    await vi.waitFor(() => expect(sortOut()).toHaveLength(0));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(heading()).not.toHaveFocus();

    await act(async () => vi.advanceTimersByTimeAsync(FADE_MS));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(heading()).toHaveFocus();
  });

  it('with reduced motion, a save that lands after Cancel and the row going leaves focus on the heading', async () => {
    const held = holdSave();
    await openFromTheRow();
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByRole('button', { name: 'Saving…' });

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await act(async () => held.release());

    await waitFor(() => expect(sortOut()).toHaveLength(0));
    await waitFor(() => expect(heading()).toHaveFocus());
  });
});
