import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import PlaybackColumns from './PlaybackColumns.jsx';

const row = (id, over = {}) => ({
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
    const touched = screen.getByRole('button', { name: /^Film a,/ });
    touched.dispatchEvent(new PointerEvent('pointerover', { bubbles: true, pointerType: 'touch' }));
    expect(onSelect).not.toHaveBeenCalled();
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
});
