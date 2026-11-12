import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import {
  EndOverlay,
  EnvironmentCard,
  ReconnectingNotice,
  IndicatorPill,
  Toast,
  UnavailableCard,
} from './PlayerOverlays.jsx';

describe('status overlays', () => {
  it('the indicator pill is a status carrying its text', () => {
    render(<IndicatorPill text="Original · Home" />);
    expect(screen.getByRole('status')).toHaveTextContent('Original · Home');
  });

  it('a dropped connection is announced at once as a status reading Reconnecting…, not BUFFERING', () => {
    render(<ReconnectingNotice />);
    expect(screen.getByRole('status')).toHaveTextContent('Reconnecting…');
    expect(screen.queryByText('BUFFERING')).not.toBeInTheDocument();
  });

  it('a toast is a status carrying its message', () => {
    render(<Toast>Subtitles: English</Toast>);
    expect(screen.getByRole('status')).toHaveTextContent('Subtitles: English');
  });
});

describe('UnavailableCard', () => {
  it('says the film cannot be played right now as an alert and focuses Try again', () => {
    render(<UnavailableCard onRetry={() => {}} />);
    expect(screen.getByRole('alert')).toHaveTextContent('This film can’t be played right now');
    expect(screen.getByRole('button', { name: 'Try again' })).toHaveFocus();
  });

  it('Try again calls onRetry', async () => {
    const onRetry = vi.fn();
    render(<UnavailableCard onRetry={onRetry} />);
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
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

describe('EnvironmentCard', () => {
  const NAMED =
    'CineFilm hasn’t seen this network before: Virgin Media Limited. It will remember your answer on this device.';
  const UNNAMED = 'CineFilm can’t tell which network this is, so it will ask again next time.';
  const handlers = () => ({ onTrain: vi.fn(), onAway: vi.fn(), onNotNow: vi.fn() });

  it('is a group named by its question and described by its text', () => {
    render(<EnvironmentCard operator="Virgin Media Limited" {...handlers()} />);
    const group = screen.getByRole('group', { name: 'Where are you watching?' });
    expect(group).toHaveAccessibleDescription(NAMED);
    expect(within(group).getByRole('heading', { level: 2 })).toHaveTextContent(
      'Where are you watching?',
    );
    expect(within(group).getByText(NAMED)).toBeInTheDocument();
  });

  it('says it cannot tell which network this is when there is no name', () => {
    render(<EnvironmentCard operator={null} {...handlers()} />);
    expect(
      screen.getByRole('group', { name: 'Where are you watching?' }),
    ).toHaveAccessibleDescription(UNNAMED);
  });

  it('has three plain buttons in order: On a train, Hotel or away, Not now', () => {
    render(<EnvironmentCard operator={null} {...handlers()} />);
    const buttons = within(screen.getByRole('group')).getAllByRole('button');
    expect(buttons.map((b) => b.textContent)).toEqual(['On a train', 'Hotel or away', 'Not now']);
    for (const button of buttons) expect(button).toHaveClass('btn--secondary');
    for (const button of buttons) expect(button).not.toHaveClass('btn--primary');
  });

  it('takes no focus', () => {
    render(<EnvironmentCard operator="Virgin Media Limited" {...handlers()} />);
    for (const button of screen.getAllByRole('button')) expect(button).not.toHaveFocus();
    expect(document.body).toHaveFocus();
  });

  it('is not a status: the player announces it', () => {
    render(<EnvironmentCard operator="Virgin Media Limited" {...handlers()} />);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('calls the handler of the button pressed, and no other', async () => {
    const h = handlers();
    render(<EnvironmentCard operator={null} {...h} />);
    await userEvent.click(screen.getByRole('button', { name: 'On a train' }));
    expect([
      h.onTrain.mock.calls.length,
      h.onAway.mock.calls.length,
      h.onNotNow.mock.calls.length,
    ]).toEqual([1, 0, 0]);
    await userEvent.click(screen.getByRole('button', { name: 'Hotel or away' }));
    expect([
      h.onTrain.mock.calls.length,
      h.onAway.mock.calls.length,
      h.onNotNow.mock.calls.length,
    ]).toEqual([1, 1, 0]);
    await userEvent.click(screen.getByRole('button', { name: 'Not now' }));
    expect([
      h.onTrain.mock.calls.length,
      h.onAway.mock.calls.length,
      h.onNotNow.mock.calls.length,
    ]).toEqual([1, 1, 1]);
  });
});
