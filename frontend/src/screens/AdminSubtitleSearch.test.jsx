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
  renderApp('/admin/library');
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
    installFetch((method, url) => {
      if (url !== '/api/admin/scan') return null;
      polls += 1;
      return respond(
        polls < 3
          ? { ...fixtures.adminScan, openSubtitlesRunning: true }
          : { ...fixtures.adminScan, openSubtitlesLastSearchedAt: '2026-11-13T06:05:00Z' },
      );
    });
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderApp('/admin/library');
    const card = await screen.findByRole('region', { name: 'Library scan' });
    expect(await within(card).findByText('Searching for subtitles…')).toBeVisible();

    await act(() => vi.advanceTimersByTimeAsync(1500));
    await act(() => vi.advanceTimersByTimeAsync(1500));

    expect(await within(card).findByText('Subtitles searched 13 Nov 06:05')).toBeVisible();
    expect(within(card).queryByText('Searching for subtitles…')).not.toBeInTheDocument();
  });

  it('reads the subtitle files again when a search finishes, so the new evidence shows on Subtitles', async () => {
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
    renderApp('/admin/subtitles');
    await screen.findByRole('region', { name: 'Subtitles' });
    const before = fileReads;

    await act(() => vi.advanceTimersByTimeAsync(1500));
    await act(() => vi.advanceTimersByTimeAsync(1500));

    await waitFor(() => expect(fileReads).toBeGreaterThan(before));
  });

  it('keeps polling while the checks run after the search, then reads the files again and shows the verdicts', async () => {
    const unchecked = { ...fixtures.adminSubtitles[0], check: null };
    const checked = {
      ...unchecked,
      check: { ...fixtures.adminSubtitles[0].check, verdict: 'CHECK', reasons: ['Too few lines'] },
    };
    let polls = 0;
    let checksDone = false;
    installFetch((method, url) => {
      if (url === '/api/admin/subtitles') return respond([checksDone ? checked : unchecked]);
      if (url !== '/api/admin/scan') return null;
      polls += 1;
      // The search has ended (its time is set) but the check run goes on, then ends later.
      checksDone = polls >= 3;
      return respond({
        ...fixtures.adminScan,
        subtitleChecksRunning: !checksDone,
        subtitleChecksLastFinishedAt: checksDone ? '2026-11-13T06:09:00Z' : null,
      });
    });
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderApp('/admin/subtitles');
    const panel = await screen.findByRole('region', { name: 'Subtitles' });
    expect(await within(panel).findByText('Unchecked')).toBeVisible();

    await act(() => vi.advanceTimersByTimeAsync(1500));
    await act(() => vi.advanceTimersByTimeAsync(1500));

    expect(await within(panel).findByText('Needs checking')).toBeVisible();
    expect(within(panel).getByText('Too few lines')).toBeVisible();
    expect(within(panel).queryByText('Unchecked')).not.toBeInTheDocument();
  });
});
