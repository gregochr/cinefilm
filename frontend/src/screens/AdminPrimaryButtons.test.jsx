import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NETWORKS_KEY } from '../state/networks.js';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';
import { installStorage } from '../test/storage.js';

afterEach(() => {
  vi.unstubAllGlobals();
});

// A file a download replaced: the row that offers "Use my file (looks right)".
const replaced = {
  ...fixtures.adminSubtitles[0],
  id: 1,
  origin: 'FILE',
  release: null,
  openSubtitlesFileId: null,
  servedReason: 'Replaced by a download',
  served: false,
  replacedByDownload: true,
  verified: false,
  offsetSource: null,
  alignment: null,
  check: null,
};

const SUBTITLE_BUTTONS = [
  'Needed, none yet (2)',
  'Checking online (1)',
  'Unknown (1)',
  'Not needed (2)',
  'All (7)',
  'Needed: Taken (2008)',
  'Not needed: Taken (2008)',
  'Automatic: Taken (2008)',
  'Needed: Das Boot (1981)',
  'Not needed: Das Boot (1981)',
  'Automatic: Das Boot (1981)',
  'Needed (2)',
  'Unsure (1)',
  'Not needed (1)',
  'Full subtitles needed: Das Boot (1981)',
  'Full subtitles not needed: Das Boot (1981)',
  'Full subtitles automatic: Das Boot (1981)',
  'Show the full subtitle files of Das Boot (1981)',
  'Full subtitles needed: Rashomon (1950)',
  'Full subtitles not needed: Rashomon (1950)',
  'Full subtitles automatic: Rashomon (1950)',
  'Show the full subtitle files of Rashomon (1950)',
  'All (1)',
  'Forced (1)',
  'Full (0)',
  'Needs checking (0)',
  'Unchecked (0)',
  'Downloads (0)',
  'Use my file (looks right)',
  'Move Heat.en.forced.srt to another film',
  'Show the cues of Heat.en.forced.srt',
];

const LIBRARY_BUTTONS = [
  'Scan library now',
  'Refresh all ratings',
  'Import from Plex again',
  'Import credits from Plex',
];

// Every button each section renders, by accessible name, in page order.
const CASES = [
  { path: '/admin', buttons: ['Find the film…', 'Sort out editions…'] },
  { path: '/admin/subtitles', buttons: SUBTITLE_BUTTONS },
  { path: '/admin/library', buttons: LIBRARY_BUTTONS },
  {
    path: '/admin/library (Plex unreachable)',
    route: '/admin/library',
    plexReachable: false,
    buttons: [
      'Scan library now',
      'Refresh all ratings',
      'Import from Plex again',
      'Try connecting to Plex',
      'Import credits from Plex',
    ],
  },
  {
    path: '/admin/playback',
    network: true,
    buttons: ['Stop this stream: Heat', 'Forget AS1 Example'],
  },
  { path: '/admin/settings', buttons: ['Sign out'] },
];

describe('Admin: the only amber button', () => {
  it.each(CASES)('is "Scan library now" alone on $path, with every button rendered', async (c) => {
    const storage = installStorage();
    if (c.network) {
      storage.setItem(
        NETWORKS_KEY,
        JSON.stringify([{ operator: 'AS1 Example', environment: 'away' }]),
      );
    }
    installFetch((m, url) => {
      if (url === '/api/admin/subtitles') return respond([replaced]);
      if (url === '/api/admin/plex') {
        return respond({ ...fixtures.adminPlex, reachable: c.plexReachable ?? true });
      }
      return null;
    });
    renderApp(c.route ?? c.path);
    for (const name of c.buttons) await screen.findByRole('button', { name });

    const rendered = screen.getAllByRole('button');
    expect(rendered.map((b) => b.getAttribute('aria-label') ?? b.textContent)).toEqual(c.buttons);
    // A style-only rule has no role, state or text to query, so this reads the variant class
    // (the toHaveClass exception in test-standards.md, "Queries").
    for (const button of rendered) {
      if (button.textContent === 'Scan library now') {
        expect(button).toHaveClass('btn--primary');
      } else {
        expect(button, button.textContent).not.toHaveClass('btn--primary');
      }
    }
  });
});
