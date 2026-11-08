import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, SESSION_EXPIRED_EVENT, get, post } from './client.js';
import { buildFilmsQuery } from './resources.js';

afterEach(() => vi.unstubAllGlobals());

const reply = (status) => vi.fn(async () => new Response(null, { status }));

describe('api client edge cases', () => {
  it('treats a 401 from a path that merely mentions the session endpoint as an expiry', async () => {
    vi.stubGlobal('fetch', reply(401));
    const listener = vi.fn();
    window.addEventListener(SESSION_EXPIRED_EVENT, listener);
    await expect(get('/api/films?from=/api/session')).rejects.toThrow('Unauthorised');
    window.removeEventListener(SESSION_EXPIRED_EVENT, listener);
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('rejects a 401 with the Unauthorised message, not the generic one', async () => {
    vi.stubGlobal('fetch', reply(401));
    const error = await post('/api/session', { password: 'x' }).catch((e) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error.message).toBe('Unauthorised');
  });

  it('leaves null and empty search values out of the films query', () => {
    expect(buildFilmsQuery({ q: null, sort: 'year', genre: '', decade: undefined })).toBe(
      '?sort=year',
    );
    expect(buildFilmsQuery({ decade: 0 })).toBe('?decade=0');
  });
});
