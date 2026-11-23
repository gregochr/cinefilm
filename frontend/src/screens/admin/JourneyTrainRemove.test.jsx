import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import JourneyTrainRemove from './JourneyTrainRemove.jsx';

const journey = (over = {}) => ({
  id: 12,
  samples: 363,
  from: '2030-03-04T22:00:00Z',
  to: '2030-03-04T23:15:00Z',
  train: { uid: 'X12345', date: '2030-03-04', headcode: '9Z99' },
  ...over,
});
const NAME = 'Remove train from the journey logged 4 Mar 2030, 22:00 to 23:15';

const shown = (over = {}) => (
  <JourneyTrainRemove
    journey={journey()}
    open={false}
    busy={false}
    problem={null}
    onOpen={() => {}}
    onKeep={() => {}}
    onConfirm={() => {}}
    {...over}
  />
);

describe('JourneyTrainRemove', () => {
  it('says Remove train… and names the journey by the row’s title in its spoken name', () => {
    render(shown());

    expect(screen.getByRole('button', { name: NAME })).toHaveTextContent('Remove train…');
  });

  it('names a journey with no samples as having none', () => {
    render(shown({ journey: journey({ from: null, to: null, samples: 0 }) }));

    expect(
      screen.getByRole('button', { name: 'Remove train from the journey with no samples' }),
    ).toBeInTheDocument();
  });

  it('asks the page to open when pressed', async () => {
    const onOpen = vi.fn();
    render(shown({ onOpen }));

    await userEvent.click(screen.getByRole('button', { name: NAME }));

    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it('labels its group and says which train goes and that the journey stays', () => {
    render(shown({ open: true }));

    expect(screen.getByRole('group', { name: 'Remove this train?' })).toHaveTextContent(
      'Remove train 9Z99 from this journey? The journey and its rounds stay.',
    );
  });

  it('has Remove train and Keep as its buttons', () => {
    render(shown({ open: true }));

    expect(screen.getByRole('button', { name: 'Remove train' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Keep' })).toBeInTheDocument();
  });

  it('reads Removing… on its first button while the request runs', () => {
    render(shown({ open: true, busy: true }));

    expect(screen.getByRole('button', { name: 'Removing…' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
  });

  it('asks the page to remove when Remove train is pressed', async () => {
    const onConfirm = vi.fn();
    render(shown({ open: true, onConfirm }));

    await userEvent.click(screen.getByRole('button', { name: 'Remove train' }));

    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it('asks the page to close on Keep', async () => {
    const onKeep = vi.fn();
    render(shown({ open: true, onKeep }));

    await userEvent.click(screen.getByRole('button', { name: 'Keep' }));

    expect(onKeep).toHaveBeenCalledTimes(1);
  });

  it('shows its problem as an alert', () => {
    render(shown({ open: true, problem: 'Couldn’t remove the train. Try again.' }));

    expect(screen.getByRole('alert')).toHaveTextContent('Couldn’t remove the train. Try again.');
  });
});
