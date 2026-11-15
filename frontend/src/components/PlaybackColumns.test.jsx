import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fixtures } from '../test/mockApi.jsx';
import PlaybackColumns from './PlaybackColumns.jsx';

/** A session from the API-shaped fixture, as another one. */
const row = (id, over = {}) => ({
  ...structuredClone(fixtures.adminPlaybackChart.sessions[0]),
  playbackId: id,
  filmTitle: `Film ${id}`,
  startedAt: '2026-11-10T19:43:05Z',
  stallCount: 0,
  stallMs: 0,
  environment: 'home',
  rungMs: { original: 100 },
  ...over,
});

const SESSIONS = [row('a'), row('b', { stallCount: 1, stallMs: 6000 }), row('c')];

describe('PlaybackColumns', () => {
  it('draws a button per session in order, the selected one alone in the tab order', () => {
    render(
      <PlaybackColumns sessions={SESSIONS} selectedId="b" onSelect={() => {}} describedBy="x" />,
    );
    const buttons = screen.getAllByRole('button');
    expect(buttons.map((b) => b.getAttribute('aria-label').split(',')[0])).toEqual([
      'Film a',
      'Film b',
      'Film c',
    ]);
    expect(buttons.map((b) => b.getAttribute('tabindex'))).toEqual(['-1', '0', '-1']);
    expect(buttons.map((b) => b.getAttribute('aria-pressed'))).toEqual(['false', 'true', 'false']);
    expect(screen.getByRole('group', { name: 'Film sessions, oldest to newest' })).toHaveAttribute(
      'aria-describedby',
      'x',
    );
  });

  it('says the stall and the quality in the label, and draws the environment letter and a dot only for a stall', () => {
    render(
      <PlaybackColumns
        sessions={[row('a'), row('b', { stallCount: 1, stallMs: 6000, environment: 'train' })]}
        selectedId="a"
        onSelect={() => {}}
      />,
    );
    expect(
      screen.getByRole('button', { name: /^Film b, .*Train\. 1 stall, 6 s\. Original 100%\.$/ }),
    ).toHaveTextContent('T');
    expect(screen.getByRole('button', { name: /^Film a, .*Home\. No stalls\./ })).toHaveTextContent(
      'H',
    );
  });

  it('selects on a click, and on a mouse entering but not a touch', async () => {
    const onSelect = vi.fn();
    const user = userEvent.setup();
    render(<PlaybackColumns sessions={SESSIONS} selectedId="a" onSelect={onSelect} />);
    await user.click(screen.getByRole('button', { name: /^Film c,/ }));
    expect(onSelect).toHaveBeenLastCalledWith('c');
    onSelect.mockClear();
    await user.hover(screen.getByRole('button', { name: /^Film b,/ }));
    expect(onSelect).toHaveBeenCalledWith('b');
    onSelect.mockClear();
    // The same pointerover, once typed as a touch and once as a mouse: only the mouse selects.
    const target = screen.getByRole('button', { name: /^Film a,/ });
    const over = (pointerType) =>
      act(() => {
        target.dispatchEvent(new PointerEvent('pointerover', { bubbles: true, pointerType }));
      });
    over('touch');
    expect(onSelect).not.toHaveBeenCalled();
    over('mouse');
    expect(onSelect).toHaveBeenCalledWith('a');
  });

  it('moves with the arrows, Home and End, stopping at the ends, and ignores other keys', async () => {
    const onSelect = vi.fn();
    const user = userEvent.setup();
    render(<PlaybackColumns sessions={SESSIONS} selectedId="b" onSelect={onSelect} />);
    screen.getByRole('button', { name: /^Film b,/ }).focus();
    await user.keyboard('{ArrowLeft}');
    expect(onSelect).toHaveBeenLastCalledWith('a');
    expect(screen.getByRole('button', { name: /^Film a,/ })).toHaveFocus();
    await user.keyboard('{ArrowLeft}');
    expect(onSelect).toHaveBeenLastCalledWith('a');
    await user.keyboard('{End}');
    expect(onSelect).toHaveBeenLastCalledWith('c');
    expect(screen.getByRole('button', { name: /^Film c,/ })).toHaveFocus();
    await user.keyboard('{ArrowRight}');
    expect(onSelect).toHaveBeenLastCalledWith('c');
    await user.keyboard('{Home}');
    expect(onSelect).toHaveBeenLastCalledWith('a');
    await user.keyboard('{ArrowRight}');
    expect(onSelect).toHaveBeenLastCalledWith('b');
    const calls = onSelect.mock.calls.length;
    await user.keyboard('q');
    expect(onSelect).toHaveBeenCalledTimes(calls);
  });

  it('draws the 100%, 50% and 0% labels and the stalls label of the board', () => {
    render(<PlaybackColumns sessions={SESSIONS} selectedId="a" onSelect={() => {}} />);
    for (const text of ['100%', '50%', '0%', 'stalls']) {
      expect(screen.getByText(text)).toBeInTheDocument();
    }
  });

  describe('scrolling the row', () => {
    const SCROLL = 'scrollLeft';
    let offsets;
    let scrollTo;
    let scrollIntoView;

    /** A row of ten columns, 50 px apart and 22 px wide, in a box 100 px wide that starts scrolled to 0. */
    beforeEach(() => {
      offsets = new WeakMap();
      Object.defineProperty(Element.prototype, SCROLL, {
        configurable: true,
        get() {
          return offsets.get(this) ?? 0;
        },
        set(value) {
          offsets.set(this, value);
        },
      });
      Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
        configurable: true,
        get() {
          return 100;
        },
      });
      Object.defineProperty(HTMLElement.prototype, 'offsetWidth', {
        configurable: true,
        get() {
          return 22;
        },
      });
      Object.defineProperty(HTMLElement.prototype, 'offsetLeft', {
        configurable: true,
        get() {
          const index = /^Film (\d+),/.exec(this.getAttribute('aria-label') ?? '')?.[1];
          return index === undefined ? 0 : Number(index) * 50;
        },
      });
      scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
      scrollIntoView = vi.fn();
      Element.prototype.scrollIntoView = scrollIntoView;
    });

    afterEach(() => {
      for (const [proto, name] of [
        [Element.prototype, SCROLL],
        [HTMLElement.prototype, 'clientWidth'],
        [HTMLElement.prototype, 'offsetWidth'],
        [HTMLElement.prototype, 'offsetLeft'],
      ]) {
        delete proto[name];
      }
      delete Element.prototype.scrollIntoView;
      scrollTo.mockRestore();
    });

    const TEN = Array.from({ length: 10 }, (_, i) => row(String(i)));
    const column = (i) => screen.getByRole('button', { name: new RegExp(`^Film ${i},`) });
    const box = () => screen.getByTestId('pchart-scroll');
    const never = () => {
      expect(scrollTo).not.toHaveBeenCalled();
      expect(scrollIntoView).not.toHaveBeenCalled();
    };

    it('brings the newest column, selected first, into view when the row is drawn', () => {
      render(<PlaybackColumns sessions={TEN} selectedId="9" onSelect={() => {}} />);
      // The column spans 450 to 472 and the box shows 100 px: its right edge is put at the box's.
      expect(box().scrollLeft).toBe(372);
      never();
    });

    it('does not move a row whose selected column is already in view', () => {
      render(<PlaybackColumns sessions={TEN} selectedId="1" onSelect={() => {}} />);
      expect(box().scrollLeft).toBe(0);
    });

    it('moves the row when a keyboard move goes past its left edge, and only then', async () => {
      const user = userEvent.setup();
      render(<PlaybackColumns sessions={TEN} selectedId="6" onSelect={() => {}} />);
      expect(box().scrollLeft).toBe(222); // 300 to 322 at the right edge
      column(6).focus();
      await user.keyboard('{ArrowLeft}');
      expect(column(5)).toHaveFocus();
      expect(box().scrollLeft).toBe(222); // 250 to 272 is inside 222 to 322
      await user.keyboard('{ArrowLeft}');
      expect(column(4)).toHaveFocus();
      expect(box().scrollLeft).toBe(200); // 200 was left of 222
      await user.keyboard('{End}');
      expect(box().scrollLeft).toBe(372);
      never();
    });

    it('does not move the row when a mouse entering a column selects it', async () => {
      const onSelect = vi.fn();
      const user = userEvent.setup();
      render(<PlaybackColumns sessions={TEN} selectedId="1" onSelect={onSelect} />);
      expect(box().scrollLeft).toBe(0);
      await user.hover(column(9));
      expect(onSelect).toHaveBeenCalledWith('9');
      expect(box().scrollLeft).toBe(0);
      never();
    });

    it('brings the selected column into view again when the sessions change', () => {
      const { rerender } = render(
        <PlaybackColumns sessions={TEN} selectedId="9" onSelect={() => {}} />,
      );
      box().scrollLeft = 0; // the viewer scrolled away
      rerender(<PlaybackColumns sessions={[...TEN]} selectedId="9" onSelect={() => {}} />);
      expect(box().scrollLeft).toBe(372);
      never();
    });

    it('does not scroll for a selection change that leaves the sessions as they were', () => {
      const { rerender } = render(
        <PlaybackColumns sessions={TEN} selectedId="9" onSelect={() => {}} />,
      );
      box().scrollLeft = 0;
      rerender(<PlaybackColumns sessions={TEN} selectedId="8" onSelect={() => {}} />);
      expect(box().scrollLeft).toBe(0);
    });
  });

  it('copes with a selection that is not a column', () => {
    render(<PlaybackColumns sessions={SESSIONS} selectedId={undefined} onSelect={() => {}} />);
    expect(screen.getAllByRole('button')).toHaveLength(3);
  });
});
