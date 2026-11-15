import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NETWORKS_KEY } from '../state/networks.js';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';
import { installStorage } from '../test/storage.js';

afterEach(() => {
  vi.unstubAllGlobals();
});

const plain = fixtures.adminSubtitles[0];

// A file a download replaced: the file list shows it with no button.
const replaced = {
  ...plain,
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

// The buttons above the file rows: the foreign-language tab's one row, Everything else, then the file filter chips.
// The two tabs themselves are role=tab, not buttons.
const SUBTITLE_TABS = [
  'Search for English subtitles…',
  'Add my own file…',
  'Show the 1 film',
  'Hide the subtitle files',
  'Your choices (2)',
  'All (1)',
  'Forced (1)',
  'Full (0)',
  'Needs checking (0)',
  'Unchecked (0)',
  'Downloads (0)',
];

const LIBRARY_BUTTONS = [
  'Scan library now',
  'Refresh all ratings',
  'Import from Plex again',
  'Last report',
  'Import credits from Plex',
];

// Every button each section renders, by accessible name, in page order.
const CASES = [
  {
    name: 'To do',
    path: '/admin',
    buttons: [
      'Find the film…',
      'Yes, it’s Suspiria (1977)',
      'Find the film…',
      'Sort out editions…',
      'Sort out editions…',
      'Search for English subtitles…',
      'Find forced subtitles…',
    ],
  },
  {
    // The file list is read-only: it adds no button of its own, whatever the file is.
    name: 'Subtitles, a file a download replaced',
    path: '/admin/subtitles',
    items: [replaced],
    buttons: SUBTITLE_TABS,
  },
  { name: 'Library', path: '/admin/library', buttons: LIBRARY_BUTTONS },
  {
    name: 'Library, Plex unreachable',
    path: '/admin/library',
    plexReachable: false,
    buttons: [
      'Scan library now',
      'Refresh all ratings',
      'Import from Plex again',
      'Try connecting to Plex',
      'Last report',
      'Import credits from Plex',
    ],
  },
  {
    name: 'Playback, with a remembered network',
    path: '/admin/playback',
    network: true,
    buttons: [
      'Stop this stream: Heat (Mac)',
      '30 days',
      '90 days',
      'All',
      'Home',
      'Away',
      'Train',
      'All',
      'Films only (3 min+)',
      'With problems',
      'Details: Heat, 14 Nov 11:58',
      'Details: Heat, 10 Nov 19:43',
      'Details: Blade Runner, 09 Nov 18:10',
      'Details: The Wicker Man, 08 Nov 21:15',
      'Details: The Wicker Man, 06 Nov 20:00',
      'Forget Example',
    ],
  },
  { name: 'Settings', path: '/admin/settings', buttons: ['Sign out'] },
];

describe('Admin: the only amber button', () => {
  it.each(CASES)(
    'is "Scan library now" alone on $name: every button these fixtures render',
    async (c) => {
      installStorage(
        c.network
          ? { [NETWORKS_KEY]: JSON.stringify([{ operator: 'AS1 Example', environment: 'away' }]) }
          : {},
      );
      installFetch((method, url) => {
        const path = new URL(url, 'http://test.local').pathname;
        if (path === '/api/admin/subtitles') return respond(c.items ?? [replaced]);
        if (path === '/api/admin/plex') {
          return respond({ ...fixtures.adminPlex, reachable: c.plexReachable ?? true });
        }
        return null;
      });
      renderApp(c.path);
      await screen.findByRole('heading', { level: 1 });
      // The file list sits behind "Show all subtitle files".
      if (c.path === '/admin/subtitles') {
        await userEvent.click(
          await screen.findByRole('button', { name: 'Show all subtitle files' }),
        );
      }
      // findAll: a name may repeat (unmatched films each have "Find the film…").
      await screen.findAllByRole('button', { name: c.buttons[0] });
      for (const name of c.buttons) await screen.findAllByRole('button', { name });

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
    },
  );
});
