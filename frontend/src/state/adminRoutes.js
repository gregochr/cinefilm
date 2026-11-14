/** The five Admin sections, in the order the rail and the phone row list them. `end` keeps To do from matching all. */
export const ADMIN_SECTIONS = [
  { key: 'todo', to: '/admin', label: 'To do', end: true },
  { key: 'subtitles', to: '/admin/subtitles', label: 'Subtitles', end: false },
  { key: 'library', to: '/admin/library', label: 'Library', end: false },
  { key: 'playback', to: '/admin/playback', label: 'Playback', end: false },
  { key: 'settings', to: '/admin/settings', label: 'Settings', end: false },
];

/** The old address of the Library scan card, before Admin had sections. */
export const OLD_SCAN_HASH = '#library-scan';

/** Where the old scan address now lives. */
export const SCAN_ROUTE = '/admin/library#scan';

/** A path as the router matches it: no trailing slash and no case. */
export function normalisePath(pathname) {
  const path = pathname.length > 1 ? pathname.replace(/\/+$/, '') : pathname;
  return path.toLowerCase();
}

/**
 * True on the five Admin pages, where the phone replaces the app header and tab bar with Admin's own "Home" link
 * and section row. An unknown address under /admin is the "Nothing here" page and keeps the app chrome.
 */
export function bareOnPhone(pathname) {
  return ADMIN_SECTIONS.some((section) => section.to === normalisePath(pathname));
}
