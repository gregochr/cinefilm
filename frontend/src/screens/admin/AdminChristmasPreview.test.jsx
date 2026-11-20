import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../../test/mockApi.jsx';
import { installStorage } from '../../test/storage.js';

const BASE = '/api/admin/christmas';
const fresh = () => structuredClone(fixtures.christmas);

/** GET answers the fixture; a PUT that leaves a film out answers with it moved to the left-out list. */
function serve() {
  return installFetch((method, url, init) => {
    if (!url.startsWith(BASE)) return null;
    if (method === 'GET') return respond(fresh());
    const tmdbId = Number(url.slice(BASE.length + 1));
    const data = fresh();
    const film = data.inWeek.find((f) => f.tmdbId === tmdbId);
    if (JSON.parse(init.body).choice === 'EXCLUDE' && film) {
      data.inWeek = data.inWeek.filter((f) => f !== film);
      data.leftOut.push({ ...film, taggedByTmdb: true });
    }
    return respond(data);
  });
}

function stubMedia(matches) {
  const listeners = new Set();
  const list = {
    matches,
    addEventListener: (type, fn) => listeners.add(fn),
    removeEventListener: (type, fn) => listeners.delete(fn),
  };
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => list),
  );
  return {
    change(next) {
      list.matches = next;
      act(() => listeners.forEach((fn) => fn()));
    },
  };
}

beforeEach(() => installStorage());
afterEach(() => vi.unstubAllGlobals());

async function open() {
  renderApp('/admin/christmas');
  await screen.findByRole('tablist', { name: 'Christmas lists' });
}

const OPEN = /Preview Home in Christmas week/;

describe('Christmas films: the preview on a desktop', () => {
  it('sits in the status card, with no back link and the long cut line', async () => {
    stubMedia(false);
    serve();
    await open();
    const card = screen.getByRole('region', { name: 'Christmas week status' });
    expect(within(card).getByRole('heading', { name: 'This week: Christmas' })).toBeInTheDocument();
    expect(within(card).getAllByRole('link')).toHaveLength(12);
    expect(screen.queryByRole('link', { name: '‹ Admin' })).not.toBeInTheDocument();
    expect(
      screen.getByText(/These 2 are in, but won't fit\. Leave out an older film/),
    ).toBeInTheDocument();
  });
});

describe('Christmas films: the preview and back link on a phone', () => {
  it('has a back link to Admin above the eyebrow, and the short cut line', async () => {
    stubMedia(true);
    serve();
    await open();
    const back = screen.getByRole('link', { name: '‹ Admin' });
    expect(back).toHaveAttribute('href', '/admin');
    const title = screen.getByRole('heading', { level: 1, name: 'Christmas films' });
    expect(back.compareDocumentPosition(title)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    expect(screen.getByText("Home shows 12. These 2 won't fit.")).toBeInTheDocument();
  });

  it('opens the preview in a sheet and Esc returns focus to its button', async () => {
    const user = userEvent.setup();
    stubMedia(true);
    serve();
    await open();
    const opener = screen.getByRole('button', { name: OPEN });
    await user.click(opener);
    expect(screen.getByRole('dialog', { name: 'Home in Christmas week' })).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });

  it('has exactly one toast region: inside the sheet while it is up, in the page after', async () => {
    const user = userEvent.setup();
    stubMedia(true);
    serve();
    await open();
    await user.click(screen.getByRole('button', { name: 'Leave out Gremlins' }));
    const regions = () => screen.getAllByRole('status', { name: 'Christmas notification' });
    expect(await screen.findByText(/Left out Gremlins/)).toBeInTheDocument();
    expect(regions()).toHaveLength(1);

    await user.click(screen.getByRole('button', { name: OPEN }));
    const sheet = screen.getByRole('dialog', { name: 'Home in Christmas week' });
    expect(regions()).toHaveLength(1);
    expect(sheet).toContainElement(regions()[0]);
    expect(within(sheet).getByRole('button', { name: 'Undo' })).toBeInTheDocument();

    await user.click(within(sheet).getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(regions()).toHaveLength(1);
  });

  it('closes the sheet and shows the inline row when the window is widened', async () => {
    const user = userEvent.setup();
    const media = stubMedia(true);
    serve();
    await open();
    await user.click(screen.getByRole('button', { name: OPEN }));
    media.change(false);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Hide preview' })).toBeInTheDocument();
  });
});
