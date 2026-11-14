import { describe, expect, it } from 'vitest';
import { ADMIN_SECTIONS, bareOnPhone } from './adminRoutes.js';

describe('ADMIN_SECTIONS', () => {
  it('lists the five sections in order, with `end` only on To do', () => {
    expect(ADMIN_SECTIONS.map((s) => [s.label, s.to, s.end])).toEqual([
      ['To do', '/admin', true],
      ['Subtitles', '/admin/subtitles', false],
      ['Library', '/admin/library', false],
      ['Playback', '/admin/playback', false],
      ['Settings', '/admin/settings', false],
    ]);
  });
});

describe('bareOnPhone', () => {
  it.each([
    '/admin',
    '/admin/',
    '/admin/subtitles',
    '/admin/library',
    '/admin/playback',
    '/admin/settings',
    '/Admin',
    '/ADMIN/Library/',
  ])('is true on %s', (path) => expect(bareOnPhone(path)).toBe(true));

  it.each(['/', '/library', '/admin/foo', '/admin/library/x', '/films/1'])(
    'is false on %s',
    (path) => expect(bareOnPhone(path)).toBe(false),
  );
});
