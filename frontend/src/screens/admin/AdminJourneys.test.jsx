import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, renderApp } from '../../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Admin: the Train journeys page', () => {
  it('has a back link to Playback, the title and the import card', () => {
    installFetch();
    renderApp('/admin/playback/journeys');

    expect(screen.getByRole('link', { name: '← Playback' })).toHaveAttribute(
      'href',
      '/admin/playback',
    );
    expect(screen.getByRole('heading', { level: 1, name: 'Train journeys' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Import a journey' })).toBeInTheDocument();
  });

  it('goes back to Playback from the link', async () => {
    installFetch();
    renderApp('/admin/playback/journeys');

    await userEvent.click(screen.getByRole('link', { name: '← Playback' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Playback' })).toBeInTheDocument();
  });
});
