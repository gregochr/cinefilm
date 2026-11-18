import { describe, expect, it } from 'vitest';
import { trimTrailingSlashes } from './paths.js';

describe('trimTrailingSlashes', () => {
  it.each([
    ['/library', '/library'],
    ['/library/', '/library'],
    ['/library///', '/library'],
    ['/a/b//c/', '/a/b//c'],
    ['/', ''],
    ['////', ''],
    ['', ''],
    ['no-slash', 'no-slash'],
    ['/library/ ', '/library/ '],
  ])('%j becomes %j', (path, expected) => {
    expect(trimTrailingSlashes(path)).toBe(expected);
  });

  it('is quick on a long run of slashes that does not end the path', () => {
    const path = `/library${'/'.repeat(200_000)}x`;
    const started = performance.now();
    expect(trimTrailingSlashes(path)).toBe(path);
    expect(performance.now() - started).toBeLessThan(
      process.env.STRYKER_MUTATOR_WORKER ? 5000 : 500,
    );
  });
});
