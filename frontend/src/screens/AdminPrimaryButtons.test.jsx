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

// A file a download replaced: the row that offers "Use my file (looks right)".
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

// The tabs and filters above the file list, then each case adds its row's buttons.
const SUBTITLE_TABS = [
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
];

const CUES = { offsetMs: 1000, cues: [{ startMs: 1500, endMs: 2500, text: 'He is behind you.' }] };

// The file rows open further controls by hand: the cues with their timing nudges, and the attach form.
const openCues = async () => {
  await userEvent.click(await screen.findByRole('button', { name: /Show the cues/ }));
  await screen.findByRole('list', { name: /Cues in/ });
};
const openCuesAndAttach = async () => {
  await openCues();
  await userEvent.click(screen.getByRole('button', { name: /^Move .* to another film$/ }));
  await screen.findByRole('form');
};

// A file row with its cues open: the timing nudges and Reset timing show, and the check button follows.
const OPEN_ROW = [
  'Move Heat.en.forced.srt to another film',
  'Hide the cues of Heat.en.forced.srt',
  'Show lines 1 second earlier',
  'Show lines 0.25 seconds earlier',
  'Show lines 0.25 seconds later',
  'Show lines 1 second later',
  'Reset timing',
];

const LIBRARY_BUTTONS = [
  'Scan library now',
  'Refresh all ratings',
  'Import from Plex again',
  'Import credits from Plex',
];

// Every button each section renders, by accessible name, in page order.
const CASES = [
  { name: 'To do', path: '/admin', buttons: ['Find the film…', 'Sort out editions…'] },
  {
    name: 'Subtitles, a file a download replaced',
    path: '/admin/subtitles',
    items: [replaced],
    buttons: [
      ...SUBTITLE_TABS,
      'Use my file (looks right)',
      'Move Heat.en.forced.srt to another film',
      'Show the cues of Heat.en.forced.srt',
    ],
  },
  {
    name: 'Subtitles, a plain unchecked file',
    path: '/admin/subtitles',
    items: [plain],
    steps: openCues,
    buttons: [...SUBTITLE_TABS, ...OPEN_ROW, 'Looks right'],
  },
  {
    name: 'Subtitles, a checked file',
    path: '/admin/subtitles',
    items: [{ ...plain, verified: true }],
    steps: openCues,
    buttons: [...SUBTITLE_TABS, ...OPEN_ROW, 'Undo'],
  },
  {
    name: 'Subtitles, cues, timing and the attach form open',
    path: '/admin/subtitles',
    items: [{ ...plain, ownerAttached: true, offsetMs: 1000 }],
    steps: openCuesAndAttach,
    buttons: [
      ...SUBTITLE_TABS,
      'Move Heat.en.forced.srt to another film',
      'Give Heat.en.forced.srt back to automatic matching',
      'Hide the cues of Heat.en.forced.srt',
      'Attach',
      'Cancel',
      ...OPEN_ROW.slice(2),
      'Looks right',
    ],
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
      'Import credits from Plex',
    ],
  },
  {
    name: 'Playback, with a remembered network',
    path: '/admin/playback',
    network: true,
    buttons: ['Stop this stream: Heat', 'Forget AS1 Example'],
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
      installFetch((m, url) => {
        const path = new URL(url, 'http://test.local').pathname;
        if (path === '/api/admin/subtitles') return respond(c.items ?? [replaced]);
        if (/^\/api\/admin\/subtitles\/\d+\/cues$/.test(path)) return respond(CUES);
        if (path === '/api/admin/plex') {
          return respond({ ...fixtures.adminPlex, reachable: c.plexReachable ?? true });
        }
        return null;
      });
      renderApp(c.path);
      await screen.findByRole('heading', { level: 1 });
      await screen.findByRole('button', { name: c.buttons[0] });
      if (c.steps) await c.steps();
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
    },
  );
});
