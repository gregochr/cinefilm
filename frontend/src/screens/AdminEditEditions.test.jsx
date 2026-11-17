import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SESSION_EXPIRED_EVENT } from '../api/client.js';
import { FADE_MS } from '../components/useDialog.js';
import { SCAN_IDLE_POLL_MS } from '../state/useAdmin.js';
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
  const railLink = (name) =>
    within(screen.getByRole('navigation', { name: 'Admin sections' })).getByRole('link', { name });
  const second = { ...fixtures.adminAttention.items[3], filmId: 7, title: 'Alien', year: 1979 };
  const BOTH = [fixtures.adminAttention.items[3], second];
  const GONE = fixtures.adminAttention.items.filter(
    (i) => !['multiple-files', 'parts-mismatch'].includes(i.kind),
  );

  // Every save is held until released; the To do list reads as BOTH until `removed`, which a scan that finishes
  // or `landReread()` sets; a re-read can be held until `landReread()`.
  function world() {
    const w = { attention: 0, removed: false, scanFinished: false, release: {}, gate: null };
    installFetch((m, url) => {
      const save = /^\/api\/admin\/films\/(\d+)\/editions$/.exec(url);
      if (m === 'PUT' && save) {
        return new Promise((resolve) => {
          w.release[save[1]] = () => resolve(respond({ filmId: Number(save[1]), newFilmIds: [] }));
        });
      }
      if (url === '/api/admin/attention') {
        w.attention += 1;
        const answer = () => respond({ items: w.removed ? GONE : BOTH });
        return w.gate ? w.gate.promise.then(answer) : answer();
      }
      if (url === '/api/admin/scan' && w.scanFinished) {
        return respond({ ...fixtures.adminScan, lastScan: { finishedAt: '2026-11-17T09:00:00Z' } });
      }
      return null;
    });
    w.holdReread = () => {
      w.gate = {};
      w.gate.promise = new Promise((resolve) => {
        w.gate.open = resolve;
      });
    };
    w.landReread = async () => {
      w.removed = true;
      await act(async () => w.gate.open());
    };
    w.finishScan = () => {
      w.removed = true;
      w.scanFinished = true;
    };
    return w;
  }

  async function openRow(index, name) {
    const attention = await screen.findByRole('region', { name: 'Sort out several files' });
    await userEvent.click(
      (await within(attention).findAllByRole('button', { name: 'Sort out editions…' }))[index],
    );
    await screen.findByRole('dialog', { name });
    await screen.findByRole('region', { name: 'Edition Extended Edition' });
  }
  const startSave = async () => {
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByRole('button', { name: 'Saving…' });
  };

  describe('with the fade running', () => {
    async function cancelledThenSaved() {
      vi.stubGlobal('matchMedia', () => ({ matches: false }));
      const w = world();
      renderApp('/admin');
      await openRow(0, 'Blade Runner · 1982');
      await startSave();
      const before = w.attention;
      const user = holdClock();
      await user.click(screen.getByRole('button', { name: 'Cancel' }));
      await act(async () => w.release[6]());
      return { w, user, before };
    }

    it('reads the lists again, once', async () => {
      const { w, before } = await cancelledThenSaved();
      expect(screen.getByRole('dialog')).toBeInTheDocument();
      await vi.waitFor(() => expect(w.attention).toBe(before + 1));

      await act(async () => vi.advanceTimersByTimeAsync(FADE_MS));

      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      await act(async () => {}); // let anything still pending land before the exact count
      expect(w.attention).toBe(before + 1);
    });

    it('says nothing, because the person cancelled', async () => {
      const { w, before } = await cancelledThenSaved();
      expect(screen.getByRole('dialog')).toBeInTheDocument();
      await vi.waitFor(() => expect(w.attention).toBe(before + 1));

      await act(async () => vi.advanceTimersByTimeAsync(FADE_MS));

      expect(screen.queryByText('Editions saved')).not.toBeInTheDocument();
    });

    it('keeps focus off the heading until the dialog is gone, then puts it there', async () => {
      vi.stubGlobal('matchMedia', () => ({ matches: false }));
      const w = world();
      renderApp('/admin');
      await openRow(0, 'Blade Runner · 1982');
      await startSave();
      w.holdReread();
      const user = holdClock();
      await user.click(screen.getByRole('button', { name: 'Cancel' }));
      await act(async () => w.release[6]());
      await w.landReread();
      await vi.waitFor(() => expect(sortOut()).toHaveLength(0));
      expect(screen.getByRole('dialog')).toBeInTheDocument();
      expect(heading()).not.toHaveFocus();

      await act(async () => vi.advanceTimersByTimeAsync(FADE_MS));

      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(heading()).toHaveFocus();
    });

    it('a re-read that lands after the dialog has gone puts focus on the heading', async () => {
      vi.stubGlobal('matchMedia', () => ({ matches: false }));
      const w = world();
      renderApp('/admin');
      await openRow(0, 'Blade Runner · 1982');
      await startSave();
      w.holdReread();
      const user = holdClock();
      await user.click(screen.getByRole('button', { name: 'Cancel' }));
      await act(async () => w.release[6]());
      await act(async () => vi.advanceTimersByTimeAsync(FADE_MS));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(sortOut()[0]).toHaveFocus();

      await w.landReread();

      await vi.waitFor(() => expect(sortOut()).toHaveLength(0));
      expect(heading()).toHaveFocus();
    });

    it('after leaving the section mid-fade, a later Cancel and list change leave focus where it is', async () => {
      vi.stubGlobal('matchMedia', () => ({ matches: false }));
      const w = world();
      renderApp('/admin');
      await openRow(0, 'Blade Runner · 1982');
      await startSave();
      const user = holdClock();
      await user.click(screen.getByRole('button', { name: 'Cancel' }));
      await act(async () => w.release[6]());

      // Leave for another section while the dialog fades, then come back and Cancel a fresh dialog.
      await user.click(railLink('Library'));
      await vi.waitFor(() =>
        expect(screen.getByRole('heading', { level: 1, name: 'Library' })).toBeInTheDocument(),
      );
      await user.click(railLink(/^To do/));
      await vi.waitFor(() => expect(sortOut()).toHaveLength(2));
      await user.click(sortOut()[0]);
      await act(async () => vi.advanceTimersByTimeAsync(0));
      await user.click(await screen.findByRole('button', { name: 'Cancel' }));
      await act(async () => vi.advanceTimersByTimeAsync(FADE_MS));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      act(() => document.activeElement.blur());

      // A scan finishing later changes the list: the rows go, and nothing sends focus to the heading.
      w.finishScan();
      await act(async () => vi.advanceTimersByTimeAsync(SCAN_IDLE_POLL_MS));
      await vi.waitFor(() => expect(sortOut()).toHaveLength(0));
      expect(heading()).not.toHaveFocus();
    });

    it('does not hand a due fallback on to the next film’s dialog', async () => {
      // Reachable only in jsdom: in a browser the fixed, focus-trapped dialog covers the rows, so another film's
      // dialog cannot open while one fades. Kept as a defence for the effect that clears the due id.
      vi.stubGlobal('matchMedia', () => ({ matches: false }));
      const w = world();
      renderApp('/admin');
      await openRow(0, 'Blade Runner · 1982');
      await startSave();
      const user = holdClock();
      await user.click(screen.getByRole('button', { name: 'Cancel' }));
      await act(async () => w.release[6]());

      // Another film's dialog replaces the fading one without the dialog ever being closed in between.
      await user.click(sortOut()[1]);
      await screen.findByRole('dialog', { name: 'Alien · 1979' });
      await act(async () => vi.advanceTimersByTimeAsync(0));
      await user.click(screen.getByRole('button', { name: 'Cancel' }));
      await act(async () => vi.advanceTimersByTimeAsync(FADE_MS));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      act(() => document.activeElement.blur());

      w.finishScan();
      await act(async () => vi.advanceTimersByTimeAsync(SCAN_IDLE_POLL_MS));
      await vi.waitFor(() => expect(sortOut()).toHaveLength(0));
      expect(heading()).not.toHaveFocus();
    });
  });

  describe('with reduced motion', () => {
    async function cancelledBeforeTheSaveLands() {
      vi.stubGlobal('matchMedia', () => ({ matches: true }));
      const w = world();
      renderApp('/admin');
      await openRow(0, 'Blade Runner · 1982');
      await startSave();
      await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      return w;
    }

    it('a save that lands after Cancel and the row going leaves focus on the heading', async () => {
      const w = await cancelledBeforeTheSaveLands();
      w.removed = true;

      await act(async () => w.release[6]());

      await waitFor(() => expect(sortOut()).toHaveLength(0));
      await waitFor(() => expect(heading()).toHaveFocus());
    });

    it('a save for another film’s dialog is not mistaken for the open one’s', async () => {
      const w = await cancelledBeforeTheSaveLands();
      await openRow(1, 'Alien · 1979');
      w.holdReread();

      await act(async () => w.release[6]());
      await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      await w.landReread();

      await waitFor(() => expect(sortOut()).toHaveLength(0));
      await waitFor(() => expect(heading()).toHaveFocus());
    });
  });
});
