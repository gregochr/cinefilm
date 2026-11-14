import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

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

const ROUTES = [
  ['/admin', 'To do'],
  ['/admin/subtitles', 'Subtitles'],
  ['/admin/library', 'Library'],
  ['/admin/playback', 'Playback'],
  ['/admin/settings', 'Settings'],
];

describe('Admin: the only amber button', () => {
  it('is "Scan library now" across every section, with data loaded', async () => {
    const primaries = [];
    for (const [path, title] of ROUTES) {
      installFetch((m, url) => (url === '/api/admin/subtitles' ? respond([replaced]) : null));
      const view = renderApp(path);
      await screen.findByRole('heading', { level: 1, name: title });
      if (path === '/admin/subtitles') {
        await screen.findByRole('button', { name: 'Use my file (looks right)' });
      }
      if (path === '/admin/library') {
        await screen.findByRole('button', { name: 'Scan library now' });
      }
      // A style-only rule has no behaviour to query, so this reads the variant class (documented test-standards exception).
      for (const button of screen.queryAllByRole('button')) {
        if (button.textContent === 'Scan library now') {
          expect(button).toHaveClass('btn--primary');
          primaries.push(button.textContent);
        } else {
          expect(button, `${path}: ${button.textContent}`).not.toHaveClass('btn--primary');
        }
      }
      view.unmount();
      vi.unstubAllGlobals();
    }
    expect(primaries).toEqual(['Scan library now']);
  });
});
