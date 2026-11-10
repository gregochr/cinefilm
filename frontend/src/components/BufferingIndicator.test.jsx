import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import BufferingIndicator from './BufferingIndicator.jsx';

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
});
afterEach(() => {
  vi.useRealTimers();
});

const advance = (ms) =>
  act(() => {
    vi.advanceTimersByTime(ms);
  });

describe('BufferingIndicator', () => {
  it('renders nothing until it has been mounted for 400 ms (one below the boundary)', () => {
    render(<BufferingIndicator />);
    advance(399);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('appears at exactly 400 ms as a polite status reading BUFFERING', () => {
    render(<BufferingIndicator />);
    advance(400);
    const status = screen.getByRole('status');
    expect(status).toHaveAttribute('aria-live', 'polite');
    expect(status).toHaveTextContent('BUFFERING');
  });

  it('honours a custom delay and label', () => {
    render(<BufferingIndicator delay={1000} label="Loading" />);
    advance(999);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    advance(1);
    expect(screen.getByRole('status')).toHaveTextContent('Loading');
  });

  it('shows at once on the first render when the delay is 0', () => {
    render(<BufferingIndicator delay={0} label="Reconnecting…" />);
    expect(screen.getByRole('status')).toHaveTextContent('Reconnecting…');
  });

  it('cancels its timer when unmounted early, so a short stall never flashes it', () => {
    const { unmount } = render(<BufferingIndicator />);
    advance(399);
    unmount();
    expect(vi.getTimerCount()).toBe(0);
    advance(1000);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('holds two identical frames so the loop is seamless, sharing one beam gradient', () => {
    render(<BufferingIndicator delay={0} />);
    const gradients = screen.getAllByTestId('buffering-gradient');
    const beams = screen.getAllByTestId('buffering-beam');
    expect(gradients).toHaveLength(1);
    expect(beams).toHaveLength(2);
    for (const beam of beams) {
      expect(beam).toHaveAttribute('fill', `url(#${gradients[0].id})`);
    }
  });

  it('gives each instance its own gradient id, safe inside url(#...)', () => {
    render(
      <>
        <BufferingIndicator delay={0} />
        <BufferingIndicator delay={0} label="Reconnecting…" />
      </>,
    );
    const ids = screen.getAllByTestId('buffering-gradient').map((g) => g.id);
    expect(ids).toHaveLength(2);
    expect(ids[0]).not.toBe(ids[1]);
    for (const id of ids) expect(id).toMatch(/^[A-Za-z0-9_-]+$/);
  });
});
