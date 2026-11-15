import { describe, expect, it } from 'vitest';
import {
  buttons,
  footerDesktop,
  footerPhone,
  libraryPage,
  playingNow,
  rememberedNetworksNote,
  railNames,
  statusStrip,
  subtitlesPage,
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
      expect(entry.source).toMatch(/^(07|AR[1-7]|README|plan|planner, 4a)$/);
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

describe('adminCopy: the Subtitles section', () => {
  const joined = (parts) => parts.map((p) => p.text).join('');

  it("keeps AR2's lead and note word for word, with the bold parts marked", () => {
    expect(joined(subtitlesPage.lead)).toBe(
      'You only need two kinds, both in English: full subtitles for films not in English, and forced subtitles (just the foreign lines) for English films with scenes in another language. CineFilm finds and downloads both by itself; this page lists only what it couldn’t.',
    );
    expect(subtitlesPage.lead.filter((p) => p.bold).map((p) => p.text)).toEqual([
      'full subtitles',
      'forced subtitles',
    ]);
    expect(joined(subtitlesPage.else.note)).toBe(
      'Subtitles look wrong on a film? Fix them from that film’s page (Subtitles → Fix…) or in the player — that’s where timing, replacing a file and “This is wrong” now live, next to the film you’re watching.',
    );
  });

  it('names the tabs as AR2 and Q3 say', () => {
    expect(subtitlesPage.tabs).toEqual({
      foreign: 'Foreign-language films',
      forced: 'Forced subtitles not found',
    });
  });

  it("words AR2's two unlabelled-audio buttons and the owner's Q3 button", () => {
    expect(buttons.itsInLanguage.label('German')).toBe('It’s in German — find subtitles');
    expect(buttons.itsEnglish).toMatchObject({
      label: 'It’s English',
      hint: '“It’s English” marks it done and stops asking',
      source: 'AR2',
    });
    expect(buttons.noForeignScenes.label).toBe('No foreign scenes');
  });

  it('words "Add my own file…" on a row with a hint that fits a film that is already fixed', () => {
    expect(buttons.addFileToFilm).toEqual({
      label: 'Add my own file…',
      hint: 'Picks one of your subtitle files for this film',
      source: 'planner, 4a',
    });
  });

  it('says AR2’s "nothing found automatically" on both pages', () => {
    expect(todoRow.noneFound).toBe('nothing found automatically on OpenSubtitles');
  });

  it('words the owner’s choices and the way back', () => {
    const copy = subtitlesPage.else;
    expect(copy.choiceLine('full', 'NOT_NEEDED')).toBe('Full subtitles: you said not needed');
    expect(copy.choiceLine('forced', 'NEEDED')).toBe('Forced subtitles: you said needed');
    expect(copy.letDecideLabel).toBe('Let CineFilm decide');
    expect(copy.letDecide('Heat (1995)', 'forced')).toBe(
      'Let CineFilm decide: Heat (1995), forced subtitles',
    );
  });

  it('counts with the right singular and plural', () => {
    expect(subtitlesPage.filmsCount(1)).toBe('1 film');
    expect(subtitlesPage.filmsCount(4)).toBe('4 films');
    expect(subtitlesPage.else.haveSubtitles(1)).toBe('foreign film has English subtitles');
    expect(subtitlesPage.else.haveSubtitles(63)).toBe('foreign films have English subtitles');
    expect(subtitlesPage.else.inEnglish(1)).toBe('film is in English');
    expect(subtitlesPage.else.inEnglish(1341)).toBe('films are in English');
    expect(subtitlesPage.else.showFilms(63)).toBe('Show the 63 films');
    expect(subtitlesPage.else.hideFilms(63)).toBe('Hide the 63 films');
    expect(subtitlesPage.else.showFilms(1)).toBe('Show the 1 film');
    expect(subtitlesPage.else.hideFilms(1)).toBe('Hide the 1 film');
    expect(subtitlesPage.else.showFilms(2)).toBe('Show the 2 films');
    expect(subtitlesPage.else.yourChoices(2)).toBe('Your choices (2)');
    expect(subtitlesPage.files.showMore(7)).toBe('Show more (7 left)');
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

  it('words the Stop hint honestly: it ends the conversion, not the playback', () => {
    expect(buttons.stopStream).toEqual({
      label: 'Stop this stream',
      hint: 'Stops converting this stream on the server; if it’s still playing, the player starts it again.',
      source: 'plan',
    });
    expect(playingNow.stopHint()).toBe(buttons.stopStream.hint);
  });

  it('has the failure and load-error lines', () => {
    expect(playingNow.stopFailed).toBe('Couldn’t stop that stream. Try again.');
    expect(playingNow.loadError).toBe('Couldn’t load what is playing.');
  });
});

describe('adminCopy libraryPage', () => {
  it('has the scan, ratings and Plex words of the README and AR3', () => {
    expect(libraryPage.scan.lastScan('12 min ago', '1m 48s')).toBe(
      'Last scan 12 min ago · took 1m 48s',
    );
    expect(libraryPage.changes.summary).toBe('What the last scan changed');
    expect(libraryPage.changes.counts(2, 1, 1)).toBe('2 split, 1 joined, 1 flagged');
    expect(libraryPage.ratings.updated('9 Nov')).toBe('IMDb and Rotten Tomatoes · updated 9 Nov');
    expect(libraryPage.plex.title).toBe('Import from Plex');
    expect(libraryPage.plex.lastReport).toBe('Last report');
    expect(libraryPage.cast.title).toBe('Cast & crew');
  });

  it('counts films with credits, with the empty library and the singular worded apart', () => {
    expect(libraryPage.cast.count(1402, 1412)).toBe('1402 of 1412 films have credits');
    expect(libraryPage.cast.count(0, 5)).toBe('0 of 5 films have credits');
    expect(libraryPage.cast.count(0, 0)).toBe('No films in the library yet');
    expect(libraryPage.cast.count(1, 1)).toBe('1 of 1 film has credits');
  });

  it('words "show more" with how many it adds', () => {
    expect(libraryPage.changes.showMore(25)).toBe('Show 25 more');
  });

  it('does not offer the TMDB credits download (owner answer Q1)', () => {
    expect(Object.keys(buttons).some((key) => /tmdb/i.test(key))).toBe(false);
  });
});
