import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
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

  it('scrolls the selected column into view on mount and when the selection changes', () => {
    const scroll = vi.fn();
    Element.prototype.scrollIntoView = scroll;
    try {
      const { rerender } = render(
        <PlaybackColumns sessions={SESSIONS} selectedId="c" onSelect={() => {}} />,
      );
      expect(scroll).toHaveBeenCalledTimes(1);
      expect(scroll.mock.contexts[0]).toBe(screen.getByRole('button', { name: /^Film c,/ }));
      expect(scroll).toHaveBeenCalledWith({ block: 'nearest', inline: 'nearest' });
      rerender(<PlaybackColumns sessions={SESSIONS} selectedId="a" onSelect={() => {}} />);
      expect(scroll.mock.contexts[1]).toBe(screen.getByRole('button', { name: /^Film a,/ }));
    } finally {
      delete Element.prototype.scrollIntoView;
    }
  });

  it('copes with a selection that is not a column', () => {
    render(<PlaybackColumns sessions={SESSIONS} selectedId={undefined} onSelect={() => {}} />);
    expect(screen.getAllByRole('button')).toHaveLength(3);
  });
});
