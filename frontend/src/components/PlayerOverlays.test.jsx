import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { BufferingSpinner, EndOverlay, IndicatorPill, Toast } from './PlayerOverlays.jsx';

describe('status overlays', () => {
  it('the indicator pill is a status carrying its text', () => {
    render(<IndicatorPill text="Original · Home" />);
    expect(screen.getByRole('status')).toHaveTextContent('Original · Home');
  });

  it('buffering is announced as a status reading BUFFERING', () => {
    render(<BufferingSpinner />);
    expect(screen.getByRole('status')).toHaveTextContent('BUFFERING');
  });

  it('a toast is a status carrying its message', () => {
    render(<Toast>Subtitles: English</Toast>);
    expect(screen.getByRole('status')).toHaveTextContent('Subtitles: English');
  });
});

describe('EndOverlay', () => {
  const setup = (props = {}) => {
    const handlers = { onWatched: vi.fn(), onLibrary: vi.fn(), onKeepWatching: vi.fn() };
    render(
      <EndOverlay title="Heat" year={1995} director="Michael Mann" {...handlers} {...props} />,
    );
    return { handlers, dialog: screen.getByRole('dialog', { name: 'Heat' }) };
  };

  it('is a non-modal dialog named for the film, with the year and director', () => {
    const { dialog } = setup();
    expect(dialog).toHaveAttribute('aria-modal', 'false');
    expect(within(dialog).getByText('The end')).toBeInTheDocument();
    expect(within(dialog).getByText('1995 · Michael Mann')).toBeInTheDocument();
  });

  it('shows only the year when the director is unknown', () => {
    const { dialog } = setup({ director: undefined });
    expect(within(dialog).getByText('1995')).toBeInTheDocument();
  });

  it('puts focus on Mark as watched', () => {
    setup();
    expect(screen.getByRole('button', { name: 'Mark as watched' })).toHaveFocus();
  });

  it('wires each action to its own handler', async () => {
    const { handlers } = setup();
    await userEvent.click(screen.getByRole('button', { name: 'Mark as watched' }));
    expect(handlers.onWatched).toHaveBeenCalledTimes(1);
    expect(handlers.onLibrary).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Back to library' }));
    expect(handlers.onLibrary).toHaveBeenCalledTimes(1);
    await userEvent.click(screen.getByRole('button', { name: 'Keep watching the credits' }));
    expect(handlers.onKeepWatching).toHaveBeenCalledTimes(1);
    expect(handlers.onWatched).toHaveBeenCalledTimes(1);
  });
});
