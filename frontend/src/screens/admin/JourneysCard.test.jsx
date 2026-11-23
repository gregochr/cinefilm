import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, renderApp } from '../../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Playback: the Train journeys card', () => {
  it('is the last card, with its sentence and a link to the page', async () => {
    installFetch();
    renderApp('/admin/playback');

    const card = await screen.findByRole('region', { name: 'Train journeys' });
    expect(
      within(card).getByText(
        'What the train’s Wi-Fi reported on a journey, kept beside the films played on it.',
      ),
    ).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: 'Open journeys…' })).toHaveAttribute(
      'href',
      '/admin/playback/journeys',
    );
    const regions = screen.getAllByRole('region');
    expect(regions.at(-1)).toBe(card);
  });

  it('reads nothing from the server of its own', async () => {
    const fetchMock = installFetch();
    renderApp('/admin/playback');
    await screen.findByRole('region', { name: 'Train journeys' });

    expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('journeys'))).toEqual([]);
  });

  it('opens the page from the link', async () => {
    installFetch();
    renderApp('/admin/playback');

    await userEvent.click(await screen.findByRole('link', { name: 'Open journeys…' }));

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Train journeys' }),
    ).toBeInTheDocument();
  });
});
