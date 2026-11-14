import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, renderApp, respond } from '../../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Admin Subtitles section', () => {
  it('is named Subtitles and holds the Subtitles card', async () => {
    installFetch();
    renderApp('/admin/subtitles');
    expect(screen.getByRole('heading', { level: 1, name: 'Subtitles' })).toBeInTheDocument();
    expect(await screen.findByRole('region', { name: 'Subtitles' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Library scan' })).not.toBeInTheDocument();
  });

  it('shows skeletons while the subtitle lists load', () => {
    installFetch(() => new Promise(() => {}));
    renderApp('/admin/subtitles');
    expect(screen.getAllByRole('status', { name: 'Loading' })).toHaveLength(SKELETONS);
  });

  it('shows an error inside the card when the subtitle files cannot load', async () => {
    installFetch((m, url) => (url === '/api/admin/subtitles' ? respond(null, 500) : null));
    renderApp('/admin/subtitles');
    expect(await screen.findByRole('region', { name: 'Subtitles' })).toBeInTheDocument();
    expect(await screen.findAllByRole('button', { name: 'Try again' })).not.toHaveLength(0);
  });
});

const SKELETONS = 1;
