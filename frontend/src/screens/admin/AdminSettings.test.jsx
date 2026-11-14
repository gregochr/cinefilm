import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, renderApp } from '../../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Admin Settings section', () => {
  it('is named Settings and holds Continue watching and Session, with the TMDB credit last', async () => {
    installFetch();
    renderApp('/admin/settings');
    expect(screen.getByRole('heading', { level: 1, name: 'Settings' })).toBeInTheDocument();
    await screen.findByRole('region', { name: 'Continue watching' });
    expect(screen.getByRole('region', { name: 'Session' })).toBeInTheDocument();
    expect(screen.getAllByRole('region')).toHaveLength(2);
    const credit = screen.getByText(
      'This product uses the TMDB API but is not endorsed or certified by TMDB.',
    );
    const session = screen.getByRole('region', { name: 'Session' });
    expect(session.compareDocumentPosition(credit)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    expect(screen.getByRole('img', { name: 'TMDB' })).toBeInTheDocument();
  });

  it('shows one skeleton while the setting loads', () => {
    installFetch(() => new Promise(() => {}));
    renderApp('/admin/settings');
    expect(screen.getAllByRole('status', { name: 'Loading' })).toHaveLength(1);
  });
});
