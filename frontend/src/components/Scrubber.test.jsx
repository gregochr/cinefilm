import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Scrubber from './Scrubber.jsx';

// A 200px-wide track starting at x=0, so clientX equals the pixel offset (jsdom has no layout).
const rect = (width) => ({
  left: 0,
  width,
  top: 0,
  height: 8,
  right: width,
  bottom: 8,
  x: 0,
  y: 0,
});
let measure;
beforeEach(() => {
  measure = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(rect(200));
});
afterEach(() => vi.restoreAllMocks());

const DURATION = 7_200_000; // 2 hours

const setup = (props = {}) => {
  const onSeek = vi.fn();
  render(<Scrubber positionMs={3_871_000} durationMs={DURATION} onSeek={onSeek} {...props} />);
  return { onSeek, slider: screen.getByRole('slider', { name: 'Seek' }) };
};

describe('Scrubber semantics', () => {
  it('is a focusable slider whose value is spoken in words', () => {
    const { slider } = setup();
    expect(slider).toHaveAttribute('tabindex', '0');
    expect(slider).toHaveAttribute('aria-valuemin', '0');
    expect(slider).toHaveAttribute('aria-valuemax', '7200');
    expect(slider).toHaveAttribute('aria-valuenow', '3871');
    expect(slider).toHaveAttribute('aria-valuetext', '1 hour 4 minutes 31 seconds');
  });

  it('speaks a position under a minute without hours or minutes', () => {
    const { slider } = setup({ positionMs: 1_000 });
    expect(slider).toHaveAttribute('aria-valuetext', '1 second');
  });
});

describe('Scrubber pointer', () => {
  it('seeks to the pressed point on the track', async () => {
    const { slider, onSeek } = setup();
    await userEvent.pointer({ keys: '[MouseLeft>]', target: slider, coords: { clientX: 100 } });
    expect(onSeek).toHaveBeenLastCalledWith(3_600_000);
  });

  it('keeps seeking while dragging and stops after release', async () => {
    const user = userEvent.setup();
    const { slider, onSeek } = setup();
    await user.pointer([
      { keys: '[MouseLeft>]', target: slider, coords: { clientX: 20 } },
      { target: slider, coords: { clientX: 50 } },
    ]);
    expect(onSeek).toHaveBeenLastCalledWith(1_800_000);
    await user.pointer({ keys: '[/MouseLeft]', target: slider, coords: { clientX: 50 } });
    onSeek.mockClear();
    await user.pointer({ target: slider, coords: { clientX: 150 } });
    expect(onSeek).not.toHaveBeenCalled();
  });

  it('clamps a drag past either end of the track', async () => {
    const { slider, onSeek } = setup();
    await userEvent.pointer({ keys: '[MouseLeft>]', target: slider, coords: { clientX: 500 } });
    expect(onSeek).toHaveBeenLastCalledWith(DURATION);
    await userEvent.pointer({ target: slider, coords: { clientX: -40 } });
    expect(onSeek).toHaveBeenLastCalledWith(0);
  });

  it('shows a time bubble while hovering and removes it on leave', async () => {
    const { slider } = setup();
    await userEvent.pointer({ target: slider, coords: { clientX: 100 } });
    expect(screen.getByText('01:00:00')).toBeInTheDocument();
    await userEvent.unhover(slider);
    expect(screen.queryByText('01:00:00')).not.toBeInTheDocument();
  });

  it('keeps the bubble while a drag is in progress even if the pointer leaves', async () => {
    const user = userEvent.setup();
    const { slider } = setup();
    await user.pointer([
      { keys: '[MouseLeft>]', target: slider, coords: { clientX: 100 } },
      { target: slider, coords: { clientX: 110 } },
    ]);
    await user.unhover(slider);
    expect(screen.getByText('01:06:00')).toBeInTheDocument();
  });

  it('seeks to the start on a track with no measured width', async () => {
    measure.mockReturnValue(rect(0));
    const { slider, onSeek } = setup();
    await userEvent.pointer({ keys: '[MouseLeft>]', target: slider, coords: { clientX: 80 } });
    expect(onSeek).toHaveBeenLastCalledWith(0);
  });
});

describe('Scrubber keyboard', () => {
  it('Home goes to the start and End to the end', async () => {
    const { slider, onSeek } = setup();
    slider.focus();
    await userEvent.keyboard('{Home}');
    expect(onSeek).toHaveBeenLastCalledWith(0);
    await userEvent.keyboard('{End}');
    expect(onSeek).toHaveBeenLastCalledWith(DURATION);
  });

  it('Page Up and Page Down move one minute forward and back', async () => {
    const { slider, onSeek } = setup();
    slider.focus();
    await userEvent.keyboard('{PageUp}');
    expect(onSeek).toHaveBeenLastCalledWith(3_931_000);
    await userEvent.keyboard('{PageDown}');
    expect(onSeek).toHaveBeenLastCalledWith(3_811_000);
  });

  it('Page Down stops at the start', async () => {
    const { slider, onSeek } = setup({ positionMs: 20_000 });
    slider.focus();
    await userEvent.keyboard('{PageDown}');
    expect(onSeek).toHaveBeenLastCalledWith(0);
  });

  it('Page Up stops at the end', async () => {
    const { slider, onSeek } = setup({ positionMs: DURATION - 10_000 });
    slider.focus();
    await userEvent.keyboard('{PageUp}');
    expect(onSeek).toHaveBeenLastCalledWith(DURATION);
  });

  it('leaves the arrow keys to the player keyboard map', async () => {
    const { slider, onSeek } = setup();
    slider.focus();
    await userEvent.keyboard('{ArrowRight}{ArrowLeft}');
    expect(onSeek).not.toHaveBeenCalled();
  });
});
