import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

// The line sits in Everything else on the Subtitles page (it left the Library scan card in 4a).
async function openElse(over) {
  installFetch((method, url) =>
    url === '/api/admin/scan' ? respond({ ...fixtures.adminScan, ...over }) : null,
  );
  renderApp('/admin/subtitles');
  return screen.findByRole('region', { name: 'Everything else' });
}

// With fake timers that advance by themselves, a click has to advance them too.
const clicker = () => userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) });

describe('Admin: the subtitle search line in Everything else', () => {
  it('says when subtitles were last searched', async () => {
    const card = await openElse({});
    expect(await within(card).findByText('Subtitles searched 12 Nov 06:00')).toBeVisible();
  });

  it('says searching while a search runs', async () => {
    const card = await openElse({ openSubtitlesRunning: true });
    expect(await within(card).findByText('Searching for subtitles…')).toBeVisible();
    expect(within(card).queryByText(/Subtitles searched/)).not.toBeInTheDocument();
  });

  it('says subtitles were not searched yet before the first search', async () => {
    const card = await openElse({ openSubtitlesLastSearchedAt: null });
    expect(await within(card).findByText('Subtitles not searched yet')).toBeVisible();
  });

  it('hides the line when the search is off', async () => {
    const card = await openElse({ openSubtitlesEnabled: false });
    await within(card).findByRole('button', { name: 'Show all subtitle files' });
    expect(within(card).queryByText(/Subtitles (searched|not searched)/)).not.toBeInTheDocument();
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
    renderApp('/admin/subtitles');
    const card = await screen.findByRole('region', { name: 'Everything else' });
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
    await clicker().click(await screen.findByRole('button', { name: 'Show all subtitle files' }));
    await screen.findByRole('region', { name: 'Subtitle files' });
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
    await clicker().click(await screen.findByRole('button', { name: 'Show all subtitle files' }));
    const panel = await screen.findByRole('region', { name: 'Subtitle files' });
    expect(await within(panel).findByText('Unchecked')).toBeVisible();

    await act(() => vi.advanceTimersByTimeAsync(1500));
    await act(() => vi.advanceTimersByTimeAsync(1500));

    expect(await within(panel).findByText('Needs checking')).toBeVisible();
    expect(within(panel).getByText('Too few lines')).toBeVisible();
    expect(within(panel).queryByText('Unchecked')).not.toBeInTheDocument();
  });
});
