import { describe, expect, it } from 'vitest';
import {
  libraryCard,
  playbackCard,
  playingNowCard,
  railCounts,
  todoHeadingCount,
  todoTotal,
} from './adminSummaryFormat.js';
import fixture from '../test/fixtures/admin-summary.json';

const NOW = Date.parse('2026-11-08T12:53:10Z'); // 12 min after the fixture's scan
const todo = (pickFilm = 0, severalFiles = 0, fullMissing = 0, forcedQuestion = 0) => ({
  pickFilm,
  severalFiles,
  fullMissing,
  forcedQuestion,
});
const summary = (over) => ({ ...fixture, ...over });
const stream = (over = {}) => ({
  playbackId: 'p',
  editionId: 1,
  filmTitle: 'Heat',
  device: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)',
  environment: 'home',
  quality: '720p',
  ...over,
});

describe('todoTotal and todoHeadingCount', () => {
  it('adds the four figures', () => {
    expect(todoTotal(fixture)).toBe(6);
    expect(todoTotal(summary({ todo: todo(1, 2, 4, 8) }))).toBe(15);
  });

  it('reads "{n} things need you", the singular at 1 and nothing at 0 or before the summary', () => {
    expect(todoHeadingCount(fixture)).toBe('6 things need you');
    expect(todoHeadingCount(summary({ todo: todo(0, 0, 1, 0) }))).toBe('1 thing needs you');
    expect(todoHeadingCount(summary({ todo: todo(0, 0, 2, 0) }))).toBe('2 things need you');
    expect(todoHeadingCount(summary({ todo: todo() }))).toBeNull();
    expect(todoHeadingCount(null)).toBeNull();
  });
});

describe('railCounts', () => {
  it('shows every count with its accessible name', () => {
    expect(railCounts(fixture)).toEqual({
      todo: { badge: '6', label: 'To do, 6 things' },
      subtitles: { badge: '2', label: 'Subtitles, 2 unresolved' },
      playback: { badge: '1 live', label: 'Playback, 1 live' },
    });
  });

  it('says "1 thing" for a single To do item', () => {
    expect(railCounts(summary({ todo: todo(1) })).todo).toEqual({
      badge: '1',
      label: 'To do, 1 thing',
    });
  });

  it('leaves out a count of 0, and shows live only from 1', () => {
    expect(railCounts(summary({ todo: todo(), subtitlesUnresolved: 0, live: 0 }))).toEqual({});
    expect(railCounts(summary({ live: 1 })).playback.badge).toBe('1 live');
    expect(railCounts(summary({ live: 3 })).playback.label).toBe('Playback, 3 live');
  });

  it('shows nothing without a summary', () => {
    expect(railCounts(null)).toEqual({});
  });
});

describe('libraryCard', () => {
  const library = (over) => ({ ...fixture.library, ...over });

  it('says when it was scanned', () => {
    expect(libraryCard(library(), NOW)).toEqual({
      text: '538 films · scanned 12 min ago',
      failed: false,
    });
  });

  it('groups thousands and says "1 film" for one', () => {
    expect(libraryCard(library({ films: 1412 }), NOW).text).toBe(
      '1,412 films · scanned 12 min ago',
    );
    expect(libraryCard(library({ films: 1 }), NOW).text).toBe('1 film · scanned 12 min ago');
  });

  it('says when no scan has finished', () => {
    expect(libraryCard(library({ lastScanFinishedAt: null }), NOW)).toEqual({
      text: '538 films · not scanned yet',
      failed: false,
    });
  });

  it('says the last scan failed, and when', () => {
    expect(libraryCard(library({ lastScanError: 'Library folder is not readable' }), NOW)).toEqual({
      text: '538 films · last scan failed 12 min ago',
      failed: true,
    });
  });

  it('says the last scan failed without a time when none is known', () => {
    expect(libraryCard(library({ lastScanError: 'boom', lastScanFinishedAt: null }), NOW)).toEqual({
      text: '538 films · last scan failed',
      failed: true,
    });
  });

  it('moves the time on with the clock, one millisecond either side of a minute', () => {
    const scan = Date.parse(fixture.library.lastScanFinishedAt);
    expect(libraryCard(library(), scan + 60_000 - 1).text).toBe('538 films · scanned just now');
    expect(libraryCard(library(), scan + 60_000).text).toBe('538 films · scanned 1 min ago');
  });
});

describe('playbackCard', () => {
  it('counts the film sessions that played smoothly', () => {
    expect(playbackCard({ films: 28, smooth: 27 })).toBe(
      'Last 30 days: 27 of 28 film sessions played smoothly',
    );
    expect(playbackCard(fixture.playback)).toBe(
      'Last 30 days: 2 of 3 film sessions played smoothly',
    );
  });

  it('says "session" for one', () => {
    expect(playbackCard({ films: 1, smooth: 1 })).toBe(
      'Last 30 days: 1 of 1 film session played smoothly',
    );
  });

  it('says nothing was watched at 0', () => {
    expect(playbackCard({ films: 0, smooth: 0 })).toBe('No films watched in the last 30 days');
  });
});

describe('playingNowCard', () => {
  it('says nothing is playing for none', () => {
    expect(playingNowCard([])).toBe('Nothing playing');
    expect(playingNowCard(undefined)).toBe('Nothing playing');
  });

  it('names the film, the device and the quality of one stream', () => {
    expect(playingNowCard(fixture.playingNow)).toBe('Heat · Mac · 720p');
  });

  it('adds "+{k} more" at exactly 2 and 3 streams, and nothing at 1', () => {
    const one = stream();
    expect(playingNowCard([one])).toBe('Heat · Mac · 720p');
    expect(playingNowCard([one, stream({ filmTitle: 'Alien' })])).toBe(
      'Heat · Mac · 720p · +1 more',
    );
    expect(
      playingNowCard([one, stream({ filmTitle: 'Alien' }), stream({ filmTitle: 'Brazil' })]),
    ).toBe('Heat · Mac · 720p · +2 more');
  });

  it('uses the newest (first) stream only', () => {
    expect(playingNowCard([stream({ filmTitle: 'Alien' }), stream()])).toBe(
      'Alien · Mac · 720p · +1 more',
    );
  });

  it('labels the quality as the player does', () => {
    expect(playingNowCard([stream({ quality: 'original' })])).toBe('Heat · Mac · Original');
    expect(playingNowCard([stream({ quality: 'auto' })])).toBe('Heat · Mac · Auto');
    expect(playingNowCard([stream({ quality: '1080p' })])).toBe('Heat · Mac · 1080p');
  });

  it('copes with a stream whose report could not be read', () => {
    expect(playingNowCard([stream({ device: null, quality: null })])).toBe('Heat · Unknown device');
  });
});
