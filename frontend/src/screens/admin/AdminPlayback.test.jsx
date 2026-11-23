import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, renderApp } from '../../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

const CARDS = [
  'Now playing',
  'How films played',
  'Playback log',
  'Conversion',
  'Remembered networks',
];

describe('Admin Playback section', () => {
  it('is named Playback and holds the five cards in order, each once', async () => {
    installFetch();
    renderApp('/admin/playback');
    expect(screen.getByRole('heading', { level: 1, name: 'Playback' })).toBeInTheDocument();
    for (const name of CARDS) await screen.findByRole('region', { name });
    expect(screen.getAllByRole('region')).toHaveLength(5);
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual(CARDS);
  });

  it('counts the log against the server’s total', async () => {
    installFetch();
    renderApp('/admin/playback');
    expect(await screen.findByText('Showing 6 of 6')).toBeInTheDocument();
  });

  it('no longer shows the old Transcoding or Playback health cards', async () => {
    installFetch();
    renderApp('/admin/playback');
    await screen.findByRole('region', { name: 'Playback log' });
    expect(screen.queryByRole('region', { name: 'Transcoding' })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Playback health' })).not.toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Recent playbacks' })).not.toBeInTheDocument();
  });

  it('shows one skeleton per card that reads, while they load', () => {
    installFetch(() => new Promise(() => {}));
    renderApp('/admin/playback');
    expect(screen.getAllByRole('status', { name: /^Loading/ })).toHaveLength(4);
  });
});
