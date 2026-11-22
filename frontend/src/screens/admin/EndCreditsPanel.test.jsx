import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { fixtures } from '../../test/mockApi.jsx';
import EndCreditsPanel from './EndCreditsPanel.jsx';

const [alien] = fixtures.adminCredits.items;
const srcs = () => screen.getAllByRole('img').map((img) => img.getAttribute('src'));

describe('EndCreditsPanel', () => {
  it('is a group named for the film, with its id, and two stills at the marker and 30 s before', () => {
    render(<EndCreditsPanel row={alien} id="panel-1" />);

    expect(screen.getByRole('group', { name: 'Stills for Alien' })).toHaveAttribute(
      'id',
      'panel-1',
    );
    expect(srcs()).toEqual([
      '/api/editions/33/still?atMs=6690000',
      '/api/editions/33/still?atMs=6720000',
    ]);
    const [earlier, atTime] = screen.getAllByRole('figure');
    expect(earlier).toHaveTextContent(/^30 s earlier · 1:51:30$/);
    expect(atTime).toHaveTextContent(/^At the time · 1:52:00$/);
  });

  it('floors the earlier still at 0 for a marker near the start', () => {
    render(<EndCreditsPanel row={{ ...alien, markerMs: 12000 }} id="p" />);

    expect(srcs()).toEqual(['/api/editions/33/still?atMs=0', '/api/editions/33/still?atMs=12000']);
  });

  it('shows no stills without a runtime, only the reason or the length line', () => {
    const { rerender } = render(
      <EndCreditsPanel
        row={{
          ...alien,
          runtimeMs: null,
          markerMs: null,
          plex: { state: 'STALE', markerMs: null },
        }}
        id="p"
      />,
    );
    expect(screen.queryAllByRole('img')).toHaveLength(0);
    expect(screen.getByText('Plex’s marker is for an older copy of this file')).toBeInTheDocument();

    rerender(<EndCreditsPanel row={{ ...alien, runtimeMs: null }} id="p" />);
    expect(screen.getByText('This film’s length isn’t known')).toBeInTheDocument();
  });

  it('shows a failed still as a message with Try again, which remounts the image and never retries alone', async () => {
    render(<EndCreditsPanel row={alien} id="p" />);
    const [first] = screen.getAllByRole('img');

    fireEvent.error(first);

    expect(screen.getByText('No still for this time')).toBeInTheDocument();
    expect(screen.getAllByRole('img')).toHaveLength(1);
    // Nothing retries on its own: once the pending work has settled the failure and the single image stand.
    await act(async () => {});
    expect(screen.getByText('No still for this time')).toBeInTheDocument();
    expect(screen.getAllByRole('img')).toHaveLength(1);
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(screen.queryByText('No still for this time')).toBeNull();
    expect(srcs()).toEqual([
      '/api/editions/33/still?atMs=6690000',
      '/api/editions/33/still?atMs=6720000',
    ]);
  });
});

describe('EndCreditsPanel: the time field, Set and Clear', () => {
  const field = () => screen.getByLabelText('End credits start at');
  const owner = { ...alien, markerMs: 6730000, source: 'OWNER' };
  const open = (row = alien, props = {}) => {
    const handlers = { onSet: vi.fn(), onClear: vi.fn(), onEdit: vi.fn() };
    render(
      <EndCreditsPanel row={row} id="p" busy={false} failure={null} {...handlers} {...props} />,
    );
    return handlers;
  };

  it('is labelled, hinted and prefilled with the panel’s time', () => {
    open();

    expect(field()).toHaveValue('1:52:00');
    expect(field()).toHaveAccessibleDescription(
      'Hours, minutes and seconds, like 1:52:10, or minutes and seconds, like 52:10',
    );
    expect(field()).not.toHaveAttribute('aria-invalid');
    expect(screen.getByRole('button', { name: 'Show stills' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Set this time' })).toBeInTheDocument();
  });

  it('reloads both stills at the typed time with Show stills, and sends nothing', async () => {
    const { onSet } = open();

    await userEvent.clear(field());
    await userEvent.type(field(), '1:54:00');
    await userEvent.click(screen.getByRole('button', { name: 'Show stills' }));

    expect(srcs()).toEqual([
      '/api/editions/33/still?atMs=6810000',
      '/api/editions/33/still?atMs=6840000',
    ]);
    expect(onSet).not.toHaveBeenCalled();
  });

  it('sends the typed time, in milliseconds, with Set this time or Enter', async () => {
    const { onSet } = open();

    await userEvent.clear(field());
    await userEvent.type(field(), '1:52:10');
    await userEvent.click(screen.getByRole('button', { name: 'Set this time' }));
    await userEvent.type(field(), '{Enter}');

    expect(onSet).toHaveBeenNthCalledWith(1, 6730000);
    expect(onSet).toHaveBeenNthCalledWith(2, 6730000);
  });

  it('refuses a time that will not parse, and one outside the guard, with the error described by the field', async () => {
    const { onSet } = open();

    await userEvent.clear(field());
    await userEvent.type(field(), '1:2:3:4');
    await userEvent.click(screen.getByRole('button', { name: 'Set this time' }));

    const message = screen.getByRole('alert');
    expect(message).toHaveTextContent('Use h:mm:ss or m:ss, for example 1:52:10');
    expect(field()).toHaveAttribute('aria-invalid', 'true');
    expect(field()).toHaveAccessibleDescription(
      `Hours, minutes and seconds, like 1:52:10, or minutes and seconds, like 52:10 ${message.textContent}`,
    );

    await userEvent.clear(field());
    await userEvent.type(field(), '1:09:35');
    await userEvent.click(screen.getByRole('button', { name: 'Set this time' }));
    // alien runs 6,960,000 ms: 60% in is 1:09:36, and 60 s before the end is 1:55:00.
    expect(screen.getByRole('alert')).toHaveTextContent(
      'That time is outside where end credits can start (1:09:36 to 1:55:00)',
    );
    await userEvent.clear(field());
    await userEvent.type(field(), '1:55:01');
    await userEvent.click(screen.getByRole('button', { name: 'Set this time' }));
    expect(screen.getByRole('alert')).toHaveTextContent('(1:09:36 to 1:55:00)');
    expect(onSet).not.toHaveBeenCalled();
  });

  it('does nothing on Set while a write is in flight, and the button stays focusable', async () => {
    const { onSet } = open(alien, { busy: true });
    const set = screen.getByRole('button', { name: 'Set this time' });

    await userEvent.click(set);

    expect(set).toHaveAttribute('aria-disabled', 'true');
    expect(onSet).not.toHaveBeenCalled();
  });

  it('shows what the server said about the time as the field’s error, and any other refusal as an alert', () => {
    const { unmount } = render(
      <EndCreditsPanel
        row={alien}
        id="p"
        busy={false}
        failure={{
          editionId: 33,
          kind: 'field',
          text: 'That time is outside where end credits can start',
        }}
        onSet={vi.fn()}
        onClear={vi.fn()}
        onEdit={vi.fn()}
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent(
      'That time is outside where end credits can start',
    );
    expect(field()).toHaveAttribute('aria-invalid', 'true');
    unmount();

    open(alien, { failure: { editionId: 33, kind: 'alert', text: 'A Plex import is running.' } });
    expect(screen.getByRole('alert')).toHaveTextContent('A Plex import is running.');
    expect(field()).not.toHaveAttribute('aria-invalid');
  });

  it('has Clear my time, with its hint, only on a Yours row', async () => {
    const first = render(
      <EndCreditsPanel
        row={alien}
        id="p"
        busy={false}
        failure={null}
        onSet={vi.fn()}
        onClear={vi.fn()}
        onEdit={vi.fn()}
      />,
    );
    expect(screen.queryByRole('button', { name: 'Clear my time' })).toBeNull();
    first.unmount();

    const { onClear } = open(owner);
    const clear = screen.getByRole('button', { name: 'Clear my time' });
    expect(clear).toHaveAccessibleDescription('Goes back to Plex’s marker, or none');
    await userEvent.click(clear);
    expect(onClear).toHaveBeenCalledTimes(1);
  });

  it('has no field and no stills without a runtime, but Clear my time on a Yours row', () => {
    open({ ...owner, runtimeMs: null });

    expect(screen.queryByLabelText('End credits start at')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Show stills' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Set this time' })).toBeNull();
    expect(screen.queryAllByRole('img')).toHaveLength(0);
    expect(screen.getByRole('button', { name: 'Clear my time' })).toBeInTheDocument();
  });
});
