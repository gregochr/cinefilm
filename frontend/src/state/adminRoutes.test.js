import { describe, expect, it } from 'vitest';
import { ADMIN_SECTIONS, FILE_LISTS, bareOnPhone, normalisePath } from './adminRoutes.js';

describe('ADMIN_SECTIONS', () => {
  it('lists the six sections in order, with `end` only on To do', () => {
    expect(ADMIN_SECTIONS.map((s) => [s.label, s.to, s.end])).toEqual([
      ['To do', '/admin', true],
      ['Subtitles', '/admin/subtitles', false],
      ['Library', '/admin/library', false],
      ['Christmas films', '/admin/christmas', false],
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
    '/admin/christmas',
    '/admin/playback',
    '/admin/settings',
    '/Admin',
    '/ADMIN/Library/',
    '/admin/library/files/protected',
    '/admin/library/files/needs-conversion/',
    '/admin/library/files/unreadable',
    '/admin/library/credits',
    '/Admin/Library/Credits/',
  ])('is true on %s', (path) => expect(bareOnPhone(path)).toBe(true));

  it.each([
    '/',
    '/library',
    '/admin/foo',
    '/admin/library/x',
    '/admin/library/files/other',
    '/admin/library/credits/x',
    '/films/1',
  ])('is false on %s', (path) => expect(bareOnPhone(path)).toBe(false));
});

describe('FILE_LISTS', () => {
  it('names the three lists the scan tiles open, each under /admin/library/files', () => {
    expect(FILE_LISTS.map((l) => [l.tile, l.kind, l.path, l.title])).toEqual([
      ['protectedFiles', 'protected', '/admin/library/files/protected', 'Protected files'],
      [
        'needsConversion',
        'needs-conversion',
        '/admin/library/files/needs-conversion',
        'Files that need converting',
      ],
      [
        'unreadable',
        'unreadable',
        '/admin/library/files/unreadable',
        'Files that could not be read',
      ],
    ]);
  });
});

describe('normalisePath', () => {
  it('drops trailing slashes and the case, and keeps a lone slash', () => {
    expect(normalisePath('/Admin/Library//')).toBe('/admin/library');
    expect(normalisePath('/admin')).toBe('/admin');
    expect(normalisePath('/')).toBe('/');
  });

  it('is quick on a long run of slashes inside the path', () => {
    const path = `/admin${'/'.repeat(200_000)}x`;
    expect(normalisePath(path)).toBe(path);
  });
});
