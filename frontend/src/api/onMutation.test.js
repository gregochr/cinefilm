import { afterEach, describe, expect, it, vi } from 'vitest';
import { del, get, getWithEtag, onMutation, post, put, request } from './client.js';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

const answer = (status = 204) => vi.fn(async () => new Response(null, { status }));
const watch = () => {
  const events = [];
  const stop = onMutation((e) => events.push(e));
  return { events, stop };
};

describe('onMutation', () => {
  it.each([
    ['POST', (p) => post(p, {})],
    ['PUT', (p) => put(p, {})],
    ['DELETE', (p) => del(p)],
    ['PATCH', (p) => request('PATCH', p, { body: {} })],
  ])('%s reports start before sending and end after', async (method, send) => {
    const order = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        order.push('fetch');
        return new Response(null, { status: 204 });
      }),
    );
    const stop = onMutation((e) => order.push(`${e.method} ${e.path} ${e.phase}`));
    await send('/api/x?y=1');
    stop();
    expect(order).toEqual([`${method} /api/x?y=1 start`, 'fetch', `${method} /api/x?y=1 end`]);
  });

  it('reports nothing for a GET or a conditional GET', async () => {
    vi.stubGlobal('fetch', answer(200));
    const { events, stop } = watch();
    await get('/api/films');
    await getWithEtag('/api/search-index', '"a"');
    stop();
    expect(events).toEqual([]);
  });

  it('reports end after an HTTP error', async () => {
    vi.stubGlobal('fetch', answer(500));
    const { events, stop } = watch();
    await expect(post('/api/x', {})).rejects.toMatchObject({ status: 500 });
    stop();
    expect(events.map((e) => e.phase)).toEqual(['start', 'end']);
  });

  it('reports end after a 401', async () => {
    vi.stubGlobal('fetch', answer(401));
    const { events, stop } = watch();
    await expect(post('/api/x', {})).rejects.toMatchObject({ status: 401 });
    stop();
    expect(events.map((e) => e.phase)).toEqual(['start', 'end']);
  });

  it('reports end after a rejected fetch', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')));
    const { events, stop } = watch();
    await expect(put('/api/x', {})).rejects.toThrow('offline');
    stop();
    expect(events.map((e) => e.phase)).toEqual(['start', 'end']);
  });

  it('reports end after an abort', async () => {
    const controller = new AbortController();
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (url, { signal }) =>
          new Promise((_, reject) =>
            signal.addEventListener('abort', () =>
              reject(Object.assign(new Error('aborted'), { name: 'AbortError' })),
            ),
          ),
      ),
    );
    const { events, stop } = watch();
    const pending = put('/api/x', {}, { signal: controller.signal });
    expect(events.map((e) => e.phase)).toEqual(['start']);
    controller.abort();
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    stop();
    expect(events.map((e) => e.phase)).toEqual(['start', 'end']);
  });

  it('reports in fixture mode too', async () => {
    vi.stubEnv('VITE_FIXTURES', '1');
    const { events, stop } = watch();
    await post('/api/session', { password: 'changeme' }).catch(() => {});
    stop();
    expect(events.map((e) => e.phase)).toEqual(['start', 'end']);
  });

  it('stops reporting once unsubscribed', async () => {
    vi.stubGlobal('fetch', answer());
    const { events, stop } = watch();
    stop();
    await post('/api/x', {});
    expect(events).toEqual([]);
  });

  it('a throwing listener does not fail the request or silence the others', async () => {
    vi.stubGlobal('fetch', answer(200));
    const stopBad = onMutation(() => {
      throw new Error('bad listener');
    });
    const { events, stop } = watch();
    await expect(post('/api/x', {})).resolves.toBeNull();
    stopBad();
    stop();
    expect(events.map((e) => e.phase)).toEqual(['start', 'end']);
  });
});
