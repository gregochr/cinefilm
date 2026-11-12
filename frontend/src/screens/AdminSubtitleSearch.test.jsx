import { act, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

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

  it('keeps polling while a search runs alone, then shows when it finished', async () => {
    let polls = 0;
    let fileReads = 0;
    installFetch((method, url) => {
      if (url === '/api/admin/subtitles') {
        fileReads += 1;
        return null;
      }
      if (url !== '/api/admin/scan') return null;
      polls += 1;
      return respond(
        polls < 3
          ? { ...fixtures.adminScan, openSubtitlesRunning: true }
          : { ...fixtures.adminScan, openSubtitlesLastSearchedAt: '2026-11-13T06:05:00Z' },
      );
    });
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderApp('/admin');
    const card = await screen.findByRole('region', { name: 'Library scan' });
    expect(await within(card).findByText('Searching for subtitles…')).toBeVisible();

    await act(() => vi.advanceTimersByTimeAsync(1500));
    await act(() => vi.advanceTimersByTimeAsync(1500));

    expect(await within(card).findByText('Subtitles searched 13 Nov 06:05')).toBeVisible();
    // The subtitle files are read again, so the new checks and evidence show.
    await waitFor(() => expect(fileReads).toBeGreaterThan(1));
    expect(within(card).queryByText('Searching for subtitles…')).not.toBeInTheDocument();
  });
});
