import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ContinueCard, LONG_PRESS_MS } from './ContinueCard.jsx';

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const film = {
  id: 1,
  title: 'Heat',
  year: 1995,
  backdropPath: '/heat.jpg',
  progress: { positionMs: 3_871_000, runtimeMs: 10_200_000, editionId: 11 },
};

function Where() {
  return <output aria-label="where">{useLocation().pathname}</output>;
}

function setup(props = {}) {
  const onRemove = vi.fn();
  const onMarkWatched = vi.fn();
  render(
    <MemoryRouter>
      <ContinueCard film={film} onRemove={onRemove} onMarkWatched={onMarkWatched} {...props} />
      <Where />
    </MemoryRouter>,
  );
  return { onRemove, onMarkWatched };
}

const more = () => screen.getByRole('button', { name: 'More options for Heat' });
const link = () => screen.getByRole('link', { name: 'Heat, 1h 45m left' });

function touchHover(none) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      matches: none,
      addEventListener: () => {},
      removeEventListener: () => {},
    })),
  );
}

describe('ContinueCard', () => {
  it('links to the film, naming the time left', () => {
    setup();
    expect(link()).toHaveAttribute('href', '/films/1');
    expect(link()).toHaveTextContent('Heat');
    expect(link()).toHaveTextContent('1h 45m left');
  });

  it('fills the progress bar to the watched fraction', () => {
    setup();
    // The fill is decorative (aria-hidden), so it carries a test-id instead of a role.
    expect(screen.getByTestId('continue-fill')).toHaveStyle({ width: '38%' });
  });

  it('copes with a film that has no progress yet', () => {
    setup({ film: { ...film, progress: undefined } });
    expect(screen.getByRole('link', { name: 'Heat,' })).toBeInTheDocument();
  });

  it('shows an empty bar when the runtime is unknown', () => {
    setup({ film: { ...film, progress: { positionMs: 1000, runtimeMs: 0, editionId: 11 } } });
    expect(screen.getByTestId('continue-fill')).toHaveStyle({ width: '0%' });
  });
});

describe('the ⋯ button', () => {
  it('is labelled, announces a menu, and sits beside the link rather than inside it', () => {
    setup();
    expect(more()).toHaveAttribute('aria-haspopup', 'menu');
    expect(more()).toHaveAttribute('aria-expanded', 'false');
    expect(link()).not.toContainElement(more());
  });

  it('opens a menu with the three actions, focuses the first, and reports expanded', async () => {
    setup();
    await userEvent.click(more());

    const menu = screen.getByRole('menu', { name: 'Options for Heat' });
    expect(more()).toHaveAttribute('aria-expanded', 'true');
    const items = screen.getAllByRole('menuitem');
    expect(items.map((i) => i.textContent)).toEqual([
      'Remove from Continue watchingKeeps your place if you come back to it',
      'Mark as watched',
      'Start from beginning',
    ]);
    expect(menu).toContainElement(items[0]);
    expect(items[0]).toHaveFocus();
    expect(screen.getByRole('menuitem', { name: 'Remove from Continue watching' })).toHaveAccessibleDescription(
      'Keeps your place if you come back to it',
    );
  });

  it('closes on a second press of ⋯', async () => {
    setup();
    await userEvent.click(more());
    await userEvent.click(more());
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(more()).toHaveAttribute('aria-expanded', 'false');
  });

  it('Enter on the focused ⋯ opens the menu', async () => {
    setup();
    more().focus();
    await userEvent.keyboard('{Enter}');
    expect(screen.getByRole('menu')).toBeInTheDocument();
  });
});

describe('the menu', () => {
  it('Esc closes it and returns focus to the ⋯ button', async () => {
    setup();
    await userEvent.click(more());
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(more()).toHaveFocus();
  });

  it('arrow keys, Home and End move between the items and wrap round', async () => {
    setup();
    await userEvent.click(more());
    const [remove, watched, start] = screen.getAllByRole('menuitem');
    await userEvent.keyboard('{ArrowDown}');
    expect(watched).toHaveFocus();
    await userEvent.keyboard('{ArrowDown}');
    expect(start).toHaveFocus();
    await userEvent.keyboard('{ArrowDown}');
    expect(remove).toHaveFocus();
    await userEvent.keyboard('{ArrowUp}');
    expect(start).toHaveFocus();
    await userEvent.keyboard('{Home}');
    expect(remove).toHaveFocus();
    await userEvent.keyboard('{End}');
    expect(start).toHaveFocus();
  });

  it('ArrowUp from outside the items goes to the last one', async () => {
    setup();
    await userEvent.click(more());
    more().focus();
    fireEvent.keyDown(screen.getByRole('menu'), { key: 'ArrowUp' });
    expect(screen.getByRole('menuitem', { name: 'Start from beginning' })).toHaveFocus();
  });

  it('closes when you click elsewhere, but not when you click inside it', async () => {
    setup();
    await userEvent.click(more());
    await userEvent.click(screen.getByRole('menu'));
    expect(screen.getByRole('menu')).toBeInTheDocument();

    await userEvent.click(document.body);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('closes when Tab leaves it, without stealing focus', async () => {
    setup();
    await userEvent.click(more());
    await userEvent.keyboard('{Tab}');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('ignores other keys', async () => {
    setup();
    await userEvent.click(more());
    await userEvent.keyboard('x');
    expect(screen.getByRole('menu')).toBeInTheDocument();
  });

  it('Remove calls onRemove with the film and closes the menu', async () => {
    const { onRemove, onMarkWatched } = setup();
    await userEvent.click(more());
    await userEvent.click(screen.getByRole('menuitem', { name: 'Remove from Continue watching' }));
    expect(onRemove).toHaveBeenCalledExactlyOnceWith(film);
    expect(onMarkWatched).not.toHaveBeenCalled();
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('Mark as watched calls onMarkWatched with the film and closes the menu', async () => {
    const { onRemove, onMarkWatched } = setup();
    await userEvent.click(more());
    await userEvent.click(screen.getByRole('menuitem', { name: 'Mark as watched' }));
    expect(onMarkWatched).toHaveBeenCalledExactlyOnceWith(film);
    expect(onRemove).not.toHaveBeenCalled();
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('Start from beginning plays edition 11 from 0 and closes the menu', async () => {
    setup();
    await userEvent.click(more());
    const start = screen.getByRole('menuitem', { name: 'Start from beginning' });
    expect(start).toHaveAttribute('href', '/films/1/play?edition=11&start=0');
    await userEvent.click(start);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });
});

describe('keys on a focused card', () => {
  it.each(['{Delete}', '{Backspace}'])('%s removes the film', async (key) => {
    const { onRemove } = setup();
    link().focus();
    await userEvent.keyboard(key);
    expect(onRemove).toHaveBeenCalledExactlyOnceWith(film);
  });

  it('m marks the film watched', async () => {
    const { onMarkWatched } = setup();
    link().focus();
    await userEvent.keyboard('m');
    expect(onMarkWatched).toHaveBeenCalledExactlyOnceWith(film);
  });

  it('ignores Shift+M, but accepts a capital M typed without Shift (Caps Lock)', () => {
    const { onMarkWatched } = setup();
    fireEvent.keyDown(link(), { key: 'M', shiftKey: true });
    expect(onMarkWatched).not.toHaveBeenCalled();
  });

  it('accepts a capital M from Caps Lock', () => {
    const { onMarkWatched } = setup();
    fireEvent.keyDown(link(), { key: 'M' });
    expect(onMarkWatched).toHaveBeenCalledExactlyOnceWith(film);
  });

  it.each([{ ctrlKey: true }, { metaKey: true }, { altKey: true }])(
    'ignores Delete and M with a modifier held (%o)',
    (modifier) => {
      const { onRemove, onMarkWatched } = setup();
      fireEvent.keyDown(link(), { key: 'Delete', ...modifier });
      fireEvent.keyDown(link(), { key: 'm', ...modifier });
      expect(onRemove).not.toHaveBeenCalled();
      expect(onMarkWatched).not.toHaveBeenCalled();
    },
  );

  it('works from the ⋯ button too, and ignores other keys', () => {
    const { onRemove } = setup();
    fireEvent.keyDown(more(), { key: 'x' });
    expect(onRemove).not.toHaveBeenCalled();
    fireEvent.keyDown(more(), { key: 'Delete' });
    expect(onRemove).toHaveBeenCalledTimes(1);
  });

  it('does not act while the menu is open', async () => {
    const { onRemove, onMarkWatched } = setup();
    await userEvent.click(more());
    await userEvent.keyboard('{Delete}m');
    expect(onRemove).not.toHaveBeenCalled();
    expect(onMarkWatched).not.toHaveBeenCalled();
  });
});

describe('on a touch device', () => {
  it('⋯ opens a bottom sheet headed with the title, year and time left, and a separate Cancel', async () => {
    touchHover(true);
    setup();
    await userEvent.click(more());

    const sheet = screen.getByRole('dialog', { name: 'Options for Heat' });
    expect(sheet).toHaveTextContent('Heat');
    expect(sheet).toHaveTextContent('1995 · 1h 45m left');
    expect(screen.getAllByRole('menuitem')).toHaveLength(3);
    const cancel = screen.getByRole('button', { name: 'Cancel' });
    expect(screen.getByRole('menu')).not.toContainElement(cancel);

    await userEvent.click(cancel);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(more()).toHaveFocus();
  });

  it('the sheet header leaves out an unknown year and time left', async () => {
    touchHover(true);
    setup({ film: { ...film, year: null, progress: undefined } });
    await userEvent.click(more());
    expect(screen.getByRole('dialog')).not.toHaveTextContent('·');
  });

  it('a hover device gets the popover, not a sheet', async () => {
    touchHover(false);
    setup();
    await userEvent.click(more());
    expect(screen.getByRole('menu')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('long press', () => {
  const touch = (n = 1) => ({ touches: Array.from({ length: n }, () => ({ clientX: 0, clientY: 0 })) });

  it('opens the sheet after 500 ms and not a millisecond sooner', () => {
    vi.useFakeTimers();
    setup();
    fireEvent.touchStart(link(), touch());
    act(() => vi.advanceTimersByTime(LONG_PRESS_MS - 1));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    act(() => vi.advanceTimersByTime(1));
    expect(LONG_PRESS_MS).toBe(500);
    expect(screen.getByRole('dialog', { name: 'Options for Heat' })).toBeInTheDocument();
  });

  it.each([
    ['touchMove', fireEvent.touchMove],
    ['touchEnd', fireEvent.touchEnd],
    ['touchCancel', fireEvent.touchCancel],
  ])('%s before 500 ms cancels it', (name, fire) => {
    vi.useFakeTimers();
    setup();
    fireEvent.touchStart(link(), touch());
    act(() => vi.advanceTimersByTime(300));
    fire(link());
    act(() => vi.advanceTimersByTime(1000));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('a two-finger touch is not a long press', () => {
    vi.useFakeTimers();
    setup();
    fireEvent.touchStart(link(), touch(2));
    act(() => vi.advanceTimersByTime(1000));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('swallows the click that follows a long press, once', () => {
    vi.useFakeTimers();
    setup();
    fireEvent.touchStart(link(), touch());
    act(() => vi.advanceTimersByTime(LONG_PRESS_MS));
    fireEvent.touchEnd(link());

    fireEvent.click(link());
    expect(screen.getByRole('status', { name: 'where' })).toHaveTextContent('/');
    fireEvent.click(link());
    expect(screen.getByRole('status', { name: 'where' })).toHaveTextContent('/films/1');
  });

  it('suppresses the context menu a long press raises, but not a normal one', () => {
    vi.useFakeTimers();
    setup();
    expect(fireEvent.contextMenu(link())).toBe(true);
    fireEvent.touchStart(link(), touch());
    act(() => vi.advanceTimersByTime(LONG_PRESS_MS));
    expect(fireEvent.contextMenu(link())).toBe(false);
  });

  it('a tap after a long press that never produced a click is a normal tap', () => {
    vi.useFakeTimers();
    setup();
    fireEvent.touchStart(link(), touch());
    act(() => vi.advanceTimersByTime(LONG_PRESS_MS));
    fireEvent.touchEnd(link());
    fireEvent.touchStart(link(), touch());
    fireEvent.touchEnd(link());
    fireEvent.click(link());
    expect(screen.getByRole('status', { name: 'where' })).toHaveTextContent('/films/1');
  });

  it('touches inside the open sheet never start a press, and its buttons are not swallowed', async () => {
    vi.useFakeTimers();
    touchHover(true);
    setup();
    fireEvent.click(more());
    const cancel = screen.getByRole('button', { name: 'Cancel' });
    fireEvent.touchStart(cancel, touch());
    act(() => vi.advanceTimersByTime(LONG_PRESS_MS));
    fireEvent.touchEnd(cancel);
    fireEvent.click(cancel);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('a long press that opens the sheet does not swallow a tap on Cancel', () => {
    vi.useFakeTimers();
    setup();
    fireEvent.touchStart(link(), touch());
    act(() => vi.advanceTimersByTime(LONG_PRESS_MS));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('clears a pending press when the card goes away', () => {
    vi.useFakeTimers();
    const { unmount } = render(
      <MemoryRouter>
        <ContinueCard film={film} onRemove={() => {}} onMarkWatched={() => {}} />
      </MemoryRouter>,
    );
    fireEvent.touchStart(link(), touch());
    unmount();
    expect(() => act(() => vi.advanceTimersByTime(1000))).not.toThrow();
  });
});
