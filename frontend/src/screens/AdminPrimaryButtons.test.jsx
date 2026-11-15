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

// The buttons above the file rows: the foreign-language tab's one row, Everything else, then the file filter chips.
// The two tabs themselves are role=tab, not buttons. Each case adds its file row's buttons.
const SUBTITLE_TABS = [
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

// An OpenSubtitles download row: not the owner's file, so no attach, and a Remove with a question behind it.
const dl = {
  ...plain,
  id: 50,
  path: 'Heat (1995)/.cinefilm/opensubtitles-50.srt',
  origin: 'OPENSUBTITLES',
  release: 'Heat.1995.1080p.BluRay.x264',
  openSubtitlesFileId: 123,
  whole: true,
  part: null,
};
// The same tabs, with the one download counted under Downloads.
const DL_TABS = SUBTITLE_TABS.map((name) => (name === 'Downloads (0)' ? 'Downloads (1)' : name));
const SHOW_DL_CUES = 'Show the cues of Heat.1995.1080p.BluRay.x264';
const REMOVE = 'Remove the OpenSubtitles download Heat.1995.1080p.BluRay.x264';
const clickRemove = async () => {
  await userEvent.click(await screen.findByRole('button', { name: REMOVE }));
  await screen.findByRole('group', { name: 'Remove this download' });
};
const failRemove = async () => {
  await clickRemove();
  await userEvent.click(screen.getByRole('button', { name: 'Remove download' }));
  await screen.findByRole('button', { name: 'Try again' });
};
const failedSearch = async () => {
  await userEvent.click(await screen.findByRole('button', { name: /^Move .* to another film$/ }));
  await userEvent.type(await screen.findByLabelText('Find a film'), 'two');
  await screen.findByText(/Couldn.t search the library/);
};

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
    ],
  },
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
  {
    name: 'Subtitles, a download before Remove',
    path: '/admin/subtitles',
    items: [dl],
    buttons: [...DL_TABS, SHOW_DL_CUES, REMOVE],
  },
  {
    name: 'Subtitles, a download asking to be removed',
    path: '/admin/subtitles',
    items: [dl],
    steps: clickRemove,
    buttons: [...DL_TABS, SHOW_DL_CUES, 'Remove download', 'Keep it'],
  },
  {
    name: 'Subtitles, a download whose removal failed',
    path: '/admin/subtitles',
    items: [dl],
    steps: failRemove,
    removeFails: true,
    buttons: [...DL_TABS, SHOW_DL_CUES, 'Try again', 'Keep it'],
  },
  {
    name: 'Subtitles, a failed film search in the attach form',
    path: '/admin/subtitles',
    items: [{ ...plain, ownerAttached: true }],
    steps: failedSearch,
    searchFails: true,
    buttons: [
      ...SUBTITLE_TABS,
      'Move Heat.en.forced.srt to another film',
      'Give Heat.en.forced.srt back to automatic matching',
      'Show the cues of Heat.en.forced.srt',
      'Try again',
      'Attach',
      'Cancel',
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
      installFetch((m, url) => {
        const path = new URL(url, 'http://test.local').pathname;
        if (path === '/api/admin/subtitles') return respond(c.items ?? [replaced]);
        if (c.removeFails && m === 'DELETE') return respond(null, 500);
        if (c.searchFails && path === '/api/films') return respond(null, 500);
        if (/^\/api\/admin\/subtitles\/\d+\/cues$/.test(path)) return respond(CUES);
        if (path === '/api/admin/plex') {
          return respond({ ...fixtures.adminPlex, reachable: c.plexReachable ?? true });
        }
        return null;
      });
      renderApp(c.path);
      await screen.findByRole('heading', { level: 1 });
      // The per-file tools sit behind "Show all subtitle files".
      if (c.path === '/admin/subtitles') {
        await userEvent.click(
          await screen.findByRole('button', { name: 'Show all subtitle files' }),
        );
      }
      // findAll: a name may repeat (unmatched films each have "Find the film…").
      await screen.findAllByRole('button', { name: c.buttons[0] });
      if (c.steps) await c.steps();
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
