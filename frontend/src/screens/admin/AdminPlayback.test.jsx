import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, renderApp } from '../../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Admin Playback section', () => {
  it('is named Playback and holds Transcoding then Playback health', async () => {
    installFetch();
    renderApp('/admin/playback');
    expect(screen.getByRole('heading', { level: 1, name: 'Playback' })).toBeInTheDocument();
    await screen.findByRole('region', { name: 'Transcoding' });
    await screen.findByRole('region', { name: 'Playback health' });
    expect(screen.getAllByRole('region')).toHaveLength(2);
  });

  it('shows one skeleton per card while they load', () => {
    installFetch(() => new Promise(() => {}));
    renderApp('/admin/playback');
    expect(screen.getAllByRole('status', { name: 'Loading' })).toHaveLength(2);
  });
});
