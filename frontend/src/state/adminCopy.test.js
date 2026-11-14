import { describe, expect, it } from 'vitest';
import {
  buttons,
  footerDesktop,
  footerPhone,
  rememberedNetworksNote,
  showAll,
  todo,
  todoGroups,
} from './adminCopy.js';

// docs/design/admin-redesign/screens/07-buttons-renamed.png, row by row: [entry, label, hint].
const SEVEN = [
  ['findFilm', 'Find the film…', 'Search TMDB and choose which film this file is.'],
  [
    'sortEditions',
    'Sort out editions…',
    'Choose which file is which cut, the part order and the default.',
  ],
  [
    'refreshRatings',
    'Refresh all ratings',
    'Re-fetches IMDb and Rotten Tomatoes scores for every film.',
  ],
  [
    'importPlex',
    'Import from Plex again',
    'Copies watched state, resume points and artwork; your newer CineFilm changes win.',
  ],
  ['tryPlex', 'Try connecting to Plex', 'Checks whether the Plex server answers now.'],
  [
    'findForced',
    'Yes, find forced subtitles…',
    'Answers the one question: does this English film have foreign dialogue?',
  ],
  [
    'letCineFilmDecide',
    'Let CineFilm decide',
    'Forgets your choice; CineFilm decides from the film’s languages again.',
  ],
  ['useMyFileInstead', 'Use my file instead', 'Plays your own file rather than the download.'],
  ['addMyFile', 'Add my own file…', 'Picks which film (and part) a subtitle file belongs to.'],
  [
    'previewLines',
    'Preview lines',
    'Shows the first lines so you can see it’s English and the right film.',
  ],
  [
    'earlier',
    'Earlier',
    'Shifts when lines appear; done while watching, where you can see the effect.',
  ],
  [
    'later',
    'Later',
    'Shifts when lines appear; done while watching, where you can see the effect.',
  ],
  [
    'deleteDownload',
    'Delete this download',
    'Deletes the downloaded file; CineFilm won’t fetch it again.',
  ],
  ['stopStream', 'Stop this stream', 'Ends playback on that device; it can resume later.'],
];

describe('adminCopy buttons', () => {
  it.each(SEVEN)('%s says what 07 says', (key, label, hint) => {
    expect(buttons[key]).toMatchObject({ label, hint, source: '07' });
  });

  it('takes Scan library now, Sign out and the busy labels from the README', () => {
    expect(buttons.scanLibrary).toEqual({
      label: 'Scan library now',
      busyLabel: 'Scanning…',
      hint: 'Looks through your movie folders for new, changed and removed files. A few minutes; you can keep watching.',
      source: 'README',
    });
    expect(buttons.signOut).toEqual({
      label: 'Sign out',
      busyLabel: 'Signing out…',
      hint: "Signing out here doesn't affect other devices.",
      source: 'README',
    });
    expect(buttons.refreshRatings.busyLabel).toBe('Refreshing ratings…');
    expect(buttons.importPlex.busyLabel).toBe('Importing…');
  });

  it('keeps Forget as Forget with no hint, and its line is the owner’s answer', () => {
    expect(buttons.forget).toEqual({ label: 'Forget', source: 'plan' });
    expect(rememberedNetworksNote).toBe(
      "Forget makes CineFilm ask where you're watching next time on that network.",
    );
  });

  it('writes the guess confirmation from a title and year', () => {
    expect(buttons.confirmGuess.label('Heat', 1995)).toBe('Yes, it’s Heat (1995)');
  });

  it('has the subtitle answers from the boards', () => {
    expect(buttons.noForeignScenes.label).toBe('No foreign scenes');
    expect(buttons.searchEnglishSubtitles.label).toBe('Search for English subtitles…');
    expect(buttons.useItAnyway.label).toBe('Use it anyway');
    expect(buttons.searchAgain.label).toBe('Search again…');
  });

  it('gives every entry a known source', () => {
    for (const entry of Object.values(buttons)) {
      expect(entry.source).toMatch(/^(07|AR[1-7]|README|plan)$/);
    }
  });
});

describe('adminCopy sections and footers', () => {
  it('has the To do intro and the four group headings in order', () => {
    expect(todo.intro).toMatch(/^Only things CineFilm can't sort out by itself\./);
    expect(todo.empty).toBe('Nothing to do.');
    expect(Object.values(todoGroups).map((g) => g.heading)).toEqual([
      'Pick the right film',
      'Sort out several files',
      'English subtitles missing',
      'Forced subtitles not found',
    ]);
    for (const group of Object.values(todoGroups)) expect(group.why).not.toBe('');
  });

  it('words the desktop and phone footers', () => {
    expect(footerDesktop(5, 12)).toBe('Showing 5 of 12');
    expect(footerPhone(1, 3)).toBe('1 of 3');
    expect(showAll(12)).toBe('Show all 12');
  });
});
