import { describe, expect, it } from 'vitest';
import {
  buttons,
  footerDesktop,
  footerPhone,
  playingNow,
  rememberedNetworksNote,
  railNames,
  statusStrip,
  todoCount,
  todoFooter,
  todoRow,
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

// reference/AR1-ToDo.dc.html: the hints under the row buttons, with no trailing full stop.
const AR1 = [
  ['confirmGuess', 'Confirms the guess and fetches its poster and details'],
  ['searchEnglishSubtitles', 'Shows OpenSubtitles results for this film so you can pick one'],
  ['useItAnyway', 'Plays that file with the film; you can adjust timing while watching'],
  ['noForeignScenes', '“No foreign scenes” marks it done and stops asking'],
];

describe('adminCopy buttons', () => {
  it.each(AR1)('%s says what AR1 says', (key, hint) => {
    expect(buttons[key]).toMatchObject({ hint, source: 'AR1' });
  });

  it("uses the owner's Q3 wording for the forced-subtitle search, not 07's", () => {
    expect(buttons.findForced).toEqual({
      label: 'Find forced subtitles…',
      source: 'plan',
    });
  });

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

  it('leaves the year out of the guess confirmation when TMDB lists none', () => {
    expect(buttons.confirmGuess.label('Dune', null)).toBe('Yes, it’s Dune');
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

  it('words the group footer controls and the row lines', () => {
    expect(todoFooter.allInSubtitles + ' ' + todoFooter.arrow).toBe('All in Subtitles →');
    expect(todoFooter.showAll).toBe('Show all');
    expect(todoFooter.showFewer).toBe('Show fewer');
    expect(todoRow.bestGuess('Heat (1995)')).toBe('best guess: Heat (1995)');
    expect(todoRow.noGuess).toBe('best guess: none');
    expect(todoRow.files(1)).toBe('1 file');
    expect(todoRow.files(3)).toBe('3 files');
    expect(Object.keys(todoRow.loadErrors)).toEqual(['attention', 'full', 'forced']);
    for (const message of Object.values(todoRow.loadErrors))
      expect(message).toMatch(/^Couldn’t load/);
    expect(todoRow.retry).toBe('Try again');
  });

  it('words the desktop and phone footers', () => {
    expect(footerDesktop(5, 12)).toBe('Showing 5 of 12');
    expect(footerPhone(1, 3)).toBe('1 of 3');
    expect(showAll(12)).toBe('Show all 12');
  });
});

describe('the To do count, rail names and status strip copy', () => {
  it('words the heading count', () => {
    expect(todoCount.plural(9)).toBe('9 things need you');
    expect(todoCount.singular).toBe('1 thing needs you');
  });

  it('words the rail names', () => {
    expect(railNames.todo(1)).toBe('To do, 1 thing');
    expect(railNames.todo(7)).toBe('To do, 7 things');
    expect(railNames.subtitles(2)).toBe('Subtitles, 2 unresolved');
    expect(railNames.playback(1)).toBe('Playback, 1 live');
    expect(railNames.live(1)).toBe('1 live');
  });

  it('words the status cards', () => {
    expect(statusStrip.library).toMatchObject({ eyebrow: 'Library', link: 'Library →' });
    expect(statusStrip.playback).toMatchObject({ eyebrow: 'Playback', link: 'Playback →' });
    expect(statusStrip.playingNow).toMatchObject({ eyebrow: 'Playing now', link: 'See stream →' });
    expect(statusStrip.scanned('538 films', '12 min ago')).toBe('538 films · scanned 12 min ago');
    expect(statusStrip.neverScanned('538 films')).toBe('538 films · not scanned yet');
    expect(statusStrip.scanFailed('538 films', '2 hours ago')).toBe(
      '538 films · last scan failed 2 hours ago',
    );
    expect(statusStrip.scanFailedUndated('538 films')).toBe('538 films · last scan failed');
    expect(statusStrip.film).toEqual({ one: 'film', other: 'films' });
    expect(statusStrip.unknownDevice).toBe('Unknown device');
    expect(statusStrip.playbackSome(27, 28)).toBe(
      'Last 30 days: 27 of 28 film sessions played smoothly',
    );
    expect(statusStrip.playbackSome(1, 1)).toBe(
      'Last 30 days: 1 of 1 film session played smoothly',
    );
    expect(statusStrip.playbackNone).toBe('No films watched in the last 30 days');
    expect(statusStrip.nothingPlaying).toBe('Nothing playing');
    expect(statusStrip.moreStreams(2)).toBe('+2 more');
    expect(statusStrip.loadError).toBe('Couldn’t load the status.');
    expect(statusStrip.retry).toBe('Try again');
  });
});

describe('adminCopy playingNow', () => {
  it('has the title, count, empty line and converting words of 04 and AR4', () => {
    expect(playingNow.title).toBe('Now playing');
    expect(playingNow.streams(1)).toBe('1 stream');
    expect(playingNow.streams(3)).toBe('3 streams');
    expect(playingNow.empty).toBe('Nothing playing.');
    expect(playingNow.converting).toBe('converting on the fly');
  });

  it('words the Stop hint as AR4 does for a device named alone', () => {
    expect(playingNow.stopHint('iPad')).toBe('Ends playback on the iPad; it can resume from here');
  });

  it('drops the article before a browser-on-system name', () => {
    expect(playingNow.stopHint('Safari on Mac')).toBe(
      'Ends playback on Safari on Mac; it can resume from here',
    );
  });

  it('falls back to 07’s line while the device is unknown', () => {
    expect(playingNow.stopHint(null)).toBe(buttons.stopStream.hint);
  });
});
