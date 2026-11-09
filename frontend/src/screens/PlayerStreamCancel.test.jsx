import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { measureThroughput } from '../player/probe.js';
import { FakeHls } from '../test/fakeHls.js';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

// The lazy hls.js import stays pending until the test releases it.
const gate = vi.hoisted(() => {
  const g = {};
  g.promise = new Promise((resolve) => {
    g.release = resolve;
  });
  return g;
});
vi.mock('hls.js', async () => {
  await gate.promise;
  return { default: (await import('../test/fakeHls.js')).FakeHls };
});
vi.mock('../player/probe.js', () => ({ measureThroughput: vi.fn() }));

beforeEach(() => {
  FakeHls.reset();
  window.MediaSource = class {};
  measureThroughput.mockReset().mockResolvedValue(null);
  sessionStorage.clear();
});
afterEach(() => {
  delete window.MediaSource;
});

describe('a stale hls.js load', () => {
  it('does not attach over the direct-play source after the viewer switched to Original', async () => {
    const noCopy = {
      ...fixtures.session,
      renditions: fixtures.session.renditions.map((r) => ({ ...r, copy: false })),
    };
    const posted = [];
    installFetch((method, url, init = {}) => {
      if (url === '/api/films/1') return respond(structuredClone(fixtures.heat));
      if (method === 'POST' && url === '/api/editions/11/sessions') {
        posted.push(JSON.parse(init.body));
        return respond(posted.length === 1 ? fixtures.session : noCopy, 201);
      }
      if (method === 'DELETE' && url.startsWith('/api/sessions/')) return respond(null, 204);
      return null;
    });
    renderApp('/films/1/play?edition=11');
    const video = await screen.findByTestId('player-video');
    await vi.waitFor(() => expect(posted).toHaveLength(1));

    // While hls.js is still loading, switch to Original: no copy rung, so the file direct-plays.
    await userEvent.click(screen.getByRole('button', { name: 'Quality' }));
    await userEvent.click(
      within(screen.getByRole('menu', { name: 'Quality' })).getByRole('menuitemradio', {
        name: /^Original/,
      }),
    );
    await vi.waitFor(() => expect(video).toHaveAttribute('src', '/api/editions/11/stream'));

    await act(async () => {
      gate.release();
      await gate.promise;
    });
    await new Promise((resolve) => setTimeout(resolve, 20));

    expect(FakeHls.instances).toHaveLength(0);
    expect(video).toHaveAttribute('src', '/api/editions/11/stream');
  });
});
