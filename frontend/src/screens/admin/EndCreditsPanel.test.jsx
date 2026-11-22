import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
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
