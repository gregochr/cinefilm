import { trimTrailingSlashes } from './paths.js';

/** The six Admin sections, in the order the rail and the phone row list them. `end` keeps To do from matching all. */
export const ADMIN_SECTIONS = [
  { key: 'todo', to: '/admin', label: 'To do', end: true },
  { key: 'subtitles', to: '/admin/subtitles', label: 'Subtitles', end: false },
  { key: 'library', to: '/admin/library', label: 'Library', end: false },
  { key: 'christmas', to: '/admin/christmas', label: 'Christmas films', end: false },
  { key: 'playback', to: '/admin/playback', label: 'Playback', end: false },
  { key: 'settings', to: '/admin/settings', label: 'Settings', end: false },
];

/**
 * The plain lists the Library scan tiles link to: the files the scan could not make playable. `tile` is the
 * `countTiles` key, `kind` the attention kind the list shows (`GET /api/admin/attention`), `path` the address.
 */
export const FILE_LISTS = [
  {
    tile: 'protectedFiles',
    kind: 'protected',
    path: '/admin/library/files/protected',
    title: 'Protected files',
  },
  {
    tile: 'needsConversion',
    kind: 'needs-conversion',
    path: '/admin/library/files/needs-conversion',
    title: 'Files that need converting',
  },
  {
    tile: 'unreadable',
    kind: 'unreadable',
    path: '/admin/library/files/unreadable',
    title: 'Files that could not be read',
  },
];

/** The End credits list, a sub-list of Library like the file lists. */
export const CREDITS_PATH = '/admin/library/credits';

/** The old address of the Library scan card, before Admin had sections. */
export const OLD_SCAN_HASH = '#library-scan';

/** Where the old scan address now lives. */
export const SCAN_ROUTE = '/admin/library#scan';

/** A path as the router matches it: no trailing slash and no case. */
export function normalisePath(pathname) {
  const path = pathname.length > 1 ? trimTrailingSlashes(pathname) : pathname;
  return path.toLowerCase();
}

/**
 * True on the five Admin pages and the three file lists under Library (`FILE_LISTS`), where the phone replaces the
 * app header and tab bar with Admin's own "Home" link and section row. An unknown address under /admin is the
 * "Nothing here" page and keeps the app chrome.
 */
export function bareOnPhone(pathname) {
  const path = normalisePath(pathname);
  return (
    ADMIN_SECTIONS.some((section) => section.to === path) ||
    FILE_LISTS.some((list) => list.path === path) ||
    path === CREDITS_PATH
  );
}
