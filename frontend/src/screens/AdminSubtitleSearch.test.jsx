import { screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => vi.unstubAllGlobals());

async function openScanCard(over) {
  installFetch((method, url) =>
    url === '/api/admin/scan' ? respond({ ...fixtures.adminScan, ...over }) : null,
  );
  renderApp('/admin');
  return screen.findByRole('region', { name: 'Library scan' });
}

describe('Admin: the subtitle search line in the Library scan card', () => {
  it('says when subtitles were last searched', async () => {
    const card = await openScanCard({});
    expect(await within(card).findByText('Subtitles searched 12 Nov 06:00')).toBeVisible();
    expect(within(card).getByText('Ratings last refreshed 08 Nov 12:41')).toBeVisible();
  });

  it('says searching while a search runs', async () => {
    const card = await openScanCard({ openSubtitlesRunning: true });
    expect(await within(card).findByText('Searching for subtitles…')).toBeVisible();
    expect(within(card).queryByText(/Subtitles searched/)).not.toBeInTheDocument();
  });

  it('says subtitles were not searched yet before the first search', async () => {
    const card = await openScanCard({ openSubtitlesLastSearchedAt: null });
    expect(await within(card).findByText('Subtitles not searched yet')).toBeVisible();
  });

  it('hides the line when the search is off', async () => {
    const card = await openScanCard({ openSubtitlesEnabled: false });
    await within(card).findByText(/Ratings last refreshed/);
    expect(within(card).queryByText(/ubtitles/)).not.toBeInTheDocument();
    expect(within(card).queryByText(/Searching/)).not.toBeInTheDocument();
  });
});
