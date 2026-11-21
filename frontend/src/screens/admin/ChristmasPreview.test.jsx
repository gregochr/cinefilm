import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PREVIEW_OPEN_KEY } from '../../state/christmasPreview.js';
import { christmasFixture } from '../../test/christmasFixture.js';
import { installStorage } from '../../test/storage.js';
import ChristmasPreview from './ChristmasPreview.jsx';

let storage;
beforeEach(() => {
  storage = installStorage();
});
afterEach(() => vi.unstubAllGlobals());

/** Holds the sheet's state the way the screen does. */
function Harness({ data, phone }) {
  const [sheetOpen, setSheetOpen] = useState(false);
  return (
    <ChristmasPreview
      data={data}
      phone={phone}
      sheetOpen={sheetOpen}
      onSheetOpenChange={setSheetOpen}
      toast={null}
    />
  );
}

const show = (data = christmasFixture(), phone = false) =>
  render(<Harness data={data} phone={phone} />, { wrapper: MemoryRouter });

const posters = () => screen.queryAllByRole('link');
const toggle = (name) => screen.getByRole('button', { name });
const FALLBACK = 'Christmas week will show the usual weekly theme — under 5 Christmas films.';

describe('ChristmasPreview on a desktop', () => {
  it('is open by default: the label, Home’s title and caption, and the first 12 posters', () => {
    show();
    expect(screen.getByText('Preview · Home in Christmas week')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'This week: Christmas' })).toBeInTheDocument();
    expect(screen.getByText('A new theme every Monday')).toBeInTheDocument();
    // 14 films are in: only 12 show.
    expect(posters()).toHaveLength(12);
    expect(screen.getByRole('link', { name: /Elf/ })).toHaveAttribute('href', '/films/12');
    expect(screen.queryByRole('link', { name: /Love Actually/ })).not.toBeInTheDocument();
    expect(toggle('Hide preview')).toHaveAttribute('aria-expanded', 'true');
  });

  it('points the toggle at the row it shows', () => {
    show();
    const id = toggle('Hide preview').getAttribute('aria-controls');
    expect(document.getElementById(id)).toContainElement(
      screen.getByRole('heading', { name: 'This week: Christmas' }),
    );
  });

  it('reserves each poster’s 2:3 art and loads its image lazily', () => {
    show();
    const first = screen.getByRole('link', { name: /Wonderful Life/ });
    const art = within(first).getByTestId('art');
    expect(art).toHaveClass('art--poster');
    expect(within(art).getByRole('presentation')).toHaveAttribute('loading', 'lazy');
  });

  it('hides and shows the row, remembering each choice', async () => {
    const user = userEvent.setup();
    show();
    await user.click(toggle('Hide preview'));
    expect(posters()).toHaveLength(0);
    expect(storage.getItem(PREVIEW_OPEN_KEY)).toBe('false');
    expect(toggle('Show preview')).toHaveAttribute('aria-expanded', 'false');
    await user.click(toggle('Show preview'));
    expect(posters()).toHaveLength(12);
    expect(storage.getItem(PREVIEW_OPEN_KEY)).toBe('true');
  });

  it('starts closed when it was left closed', () => {
    storage.setItem(PREVIEW_OPEN_KEY, 'false');
    show();
    expect(posters()).toHaveLength(0);
    expect(toggle('Show preview')).toBeInTheDocument();
    expect(screen.getByText('Preview · Home in Christmas week')).toBeInTheDocument();
  });

  it('renders open and still toggles when storage throws on every read and write', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('localStorage', {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('full');
      },
    });
    show();
    expect(posters()).toHaveLength(12);
    await user.click(toggle('Hide preview'));
    expect(posters()).toHaveLength(0);
    await user.click(toggle('Show preview'));
    expect(posters()).toHaveLength(12);
  });

  it('says the week falls back, with no posters, when fewer than 5 films are in', () => {
    const data = christmasFixture();
    data.inWeek = data.inWeek.slice(0, 4);
    data.fallsBack = true;
    show(data);
    expect(screen.getByText(FALLBACK)).toBeInTheDocument();
    expect(posters()).toHaveLength(0);
    expect(screen.queryByRole('heading', { name: 'This week: Christmas' })).not.toBeInTheDocument();
  });

  it('shows all of them when fewer than 12 are in', () => {
    const data = christmasFixture();
    data.inWeek = data.inWeek.slice(0, 6);
    show(data);
    expect(posters()).toHaveLength(6);
  });
});

describe('ChristmasPreview on a phone', () => {
  const OPEN = /Preview Home in Christmas week/;

  it('is one button, with no row until it is pressed', () => {
    show(christmasFixture(), true);
    expect(screen.getByRole('button', { name: OPEN })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Hide preview' })).not.toBeInTheDocument();
    expect(posters()).toHaveLength(0);
  });

  it('points the button at the sheet it opens', async () => {
    const user = userEvent.setup();
    show(christmasFixture(), true);
    const opener = screen.getByRole('button', { name: OPEN });
    await user.click(opener);
    expect(screen.getByRole('dialog', { name: 'Home in Christmas week' })).toHaveAttribute(
      'id',
      opener.getAttribute('aria-controls'),
    );
  });

  it('opens the row in a sheet, and Esc closes it and returns focus to the button', async () => {
    const user = userEvent.setup();
    show(christmasFixture(), true);
    const opener = screen.getByRole('button', { name: OPEN });
    await user.click(opener);
    const sheet = screen.getByRole('dialog', { name: 'Home in Christmas week' });
    expect(within(sheet).getByRole('button', { name: 'Close' })).toHaveFocus();
    expect(
      within(sheet).getByRole('heading', { name: 'This week: Christmas' }),
    ).toBeInTheDocument();
    expect(within(sheet).getAllByRole('link')).toHaveLength(12);
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });

  it('closes on Close and returns focus there too', async () => {
    const user = userEvent.setup();
    show(christmasFixture(), true);
    const opener = screen.getByRole('button', { name: OPEN });
    await user.click(opener);
    await user.click(screen.getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });

  it('says the week falls back inside the sheet', async () => {
    const user = userEvent.setup();
    const data = christmasFixture();
    data.inWeek = data.inWeek.slice(0, 3);
    data.fallsBack = true;
    show(data, true);
    await user.click(screen.getByRole('button', { name: OPEN }));
    expect(within(screen.getByRole('dialog')).getByText(FALLBACK)).toBeInTheDocument();
  });
});
