import { describe, expect, it } from 'vitest';
import {
  browseCollections,
  displayName,
  fanPosters,
  groupFilms,
  isComplete,
  sortKey,
} from './collections.js';

describe('displayName', () => {
  it('drops a trailing " Collection"', () => {
    expect(displayName('Harry Potter Collection')).toBe('Harry Potter');
    expect(displayName('  Alien collection ')).toBe('Alien');
    expect(displayName('The Lord of the Rings COLLECTION')).toBe('The Lord of the Rings');
  });

  it('leaves names without the suffix, and "Collection" elsewhere or alone, as they are', () => {
    expect(displayName('Alien')).toBe('Alien');
    expect(displayName('Collection Agency Saga')).toBe('Collection Agency Saga');
    expect(displayName('Collections of Dune')).toBe('Collections of Dune');
    expect(displayName('Collection')).toBe('Collection');
    expect(displayName()).toBe('');
  });
});

describe('sortKey', () => {
  it('lower-cases and ignores a leading "The"', () => {
    expect(sortKey('The Lord of the Rings')).toBe('lord of the rings');
    expect(sortKey('Theatre of Blood')).toBe('theatre of blood');
    expect(sortKey()).toBe('');
  });
});

describe('fanPosters', () => {
  const paths = ['/1.jpg', '/2.jpg', '/3.jpg', '/4.jpg', '/5.jpg'];

  it('shows one poster for one film, with no dimming and no shadow', () => {
    expect(fanPosters(paths.slice(0, 1))).toEqual([
      { path: '/1.jpg', depth: 0, brightness: 1, shadow: false },
    ]);
  });

  it('shows two for two: the earliest in front, the rear dimmed to 0.6', () => {
    expect(fanPosters(paths.slice(0, 2))).toEqual([
      { path: '/1.jpg', depth: 0, brightness: 1, shadow: true },
      { path: '/2.jpg', depth: 1, brightness: 0.6, shadow: false },
    ]);
  });

  it('shows three for three: 1, 0.8 and 0.6, every poster with one behind casting a shadow', () => {
    const fan = fanPosters(paths.slice(0, 3));
    expect(fan.map((s) => s.path)).toEqual(['/1.jpg', '/2.jpg', '/3.jpg']);
    expect(fan.map((s) => s.brightness)).toEqual([1, 0.8, 0.6]);
    expect(fan.map((s) => s.shadow)).toEqual([true, true, false]);
  });

  it('shows only the first three of five', () => {
    const fan = fanPosters(paths);
    expect(fan).toHaveLength(3);
    expect(fan[0].path).toBe('/1.jpg');
  });

  it('keeps a film with no artwork as a slot, and shows nothing for no films', () => {
    expect(fanPosters([null, '/2.jpg'])).toHaveLength(2);
    expect(fanPosters([null])[0].path).toBeNull();
    expect(fanPosters([])).toEqual([]);
    expect(fanPosters()).toEqual([]);
  });
});

const c = (id, name, ownedCount, totalCount, lastAddedAt) => ({
  id,
  name,
  ownedCount,
  totalCount,
  lastAddedAt,
});
const LIST = () => [
  c(1, 'The Lord of the Rings Collection', 3, 3, '2026-01-01T00:00:00Z'),
  c(2, 'Alien Collection', 2, 4, '2026-03-01T00:00:00Z'),
  c(3, 'Harry Potter Collection', 7, 8, '2026-02-01T00:00:00Z'),
  c(4, 'Anchorman', 2, 2, '2026-03-01T00:00:00Z'),
  c(5, 'Zodiac', 2, 5, null),
];
const names = (list) => list.map((x) => x.id);

describe('browseCollections', () => {
  it('sorts by name by default, ignoring a leading "The" and the Collection suffix', () => {
    expect(names(browseCollections(LIST()))).toEqual([2, 4, 3, 1, 5]);
    expect(names(browseCollections(LIST(), { sort: 'bogus' }))).toEqual([2, 4, 3, 1, 5]);
  });

  it('sorts by most recently added to, ties by name, never-added last', () => {
    expect(names(browseCollections(LIST(), { sort: 'added' }))).toEqual([2, 4, 3, 1, 5]);
  });

  it('sorts by size, largest first, ties by name', () => {
    expect(names(browseCollections(LIST(), { sort: 'size' }))).toEqual([3, 1, 2, 4, 5]);
  });

  it('Complete shows only collections where every film is owned', () => {
    expect(isComplete(LIST()[0])).toBe(true);
    expect(names(browseCollections(LIST(), { show: 'complete' }))).toEqual([4, 1]);
  });

  it('Missing films shows only collections with a film not owned', () => {
    expect(names(browseCollections(LIST(), { show: 'missing' }))).toEqual([2, 3, 5]);
  });

  it('searches the display name, case-insensitively, and does not match the stripped suffix', () => {
    expect(names(browseCollections(LIST(), { q: 'POTTER' }))).toEqual([3]);
    expect(names(browseCollections(LIST(), { q: ' an ' }))).toEqual([4]);
    expect(names(browseCollections(LIST(), { q: 'an' }))).toEqual([4]);
    expect(names(browseCollections(LIST(), { q: 'collection' }))).toEqual([]);
  });

  it('combines search, show and sort', () => {
    expect(names(browseCollections(LIST(), { q: 'a', show: 'missing', sort: 'size' }))).toEqual([
      3, 2, 5,
    ]);
  });
});

const f = (id, title, year, collectionId = null) => ({ id, title, year, collectionId });
const COLLECTIONS = [
  { id: 1, name: 'Alien Collection' },
  { id: 2, name: 'The Matrix Collection' },
];
const label = (item) => (item.type === 'film' ? item.film.title : `[${item.collection.id}]`);
const labels = (items) => items.map(label);

describe('groupFilms', () => {
  // The server's order for each sort, with Alien (1) and Matrix (2) films mixed in.
  const added = [
    f(10, 'Heat', 1995),
    f(11, 'Aliens', 1986, 1),
    f(12, 'Zodiac', 2007),
    f(13, 'Alien', 1979, 1),
    f(14, 'The Matrix', 1999, 2),
    f(15, 'Kes', 1969),
  ];

  it('folds the films of a collection into one tile at the first one under recently added', () => {
    const items = groupFilms(added, COLLECTIONS, 'added');
    expect(labels(items)).toEqual(['Heat', '[1]', 'Zodiac', '[2]', 'Kes']);
    expect(groupFilms(added, COLLECTIONS)).toEqual(items);
  });

  it('puts the tile at the earliest film under year, listing its films earliest first', () => {
    const byYearOrder = [
      f(15, 'Kes', 1969),
      f(13, 'Alien', 1979, 1),
      f(11, 'Aliens', 1986, 1),
      f(10, 'Heat', 1995),
      f(14, 'The Matrix', 1999, 2),
    ];
    const items = groupFilms(byYearOrder, COLLECTIONS, 'year');
    expect(labels(items)).toEqual(['Kes', '[1]', 'Heat', '[2]']);
    expect(items[1].films.map((x) => x.title)).toEqual(['Alien', 'Aliens']);
    expect(items[1].count).toBe(2);
  });

  it('puts a film with no known year last among a tile’s films', () => {
    const items = groupFilms(
      [f(1, 'Undated', null, 1), f(2, 'Dated', 1990, 1)],
      COLLECTIONS,
      'year',
    );
    expect(items[0].films.map((x) => x.title)).toEqual(['Dated', 'Undated']);
  });

  it('places tiles by display name among the other films under title, ignoring The', () => {
    const byTitle = [
      f(11, 'Aliens', 1986, 1),
      f(13, 'Alien', 1979, 1),
      f(10, 'Heat', 1995),
      f(15, 'Kes', 1969),
      f(14, 'The Matrix', 1999, 2),
      f(12, 'Zodiac', 2007),
    ];
    // "Alien" sorts before the film "Heat" and after nothing; "Matrix" sits between "Kes" and "Zodiac".
    expect(labels(groupFilms(byTitle, COLLECTIONS, 'title'))).toEqual([
      '[1]',
      'Heat',
      'Kes',
      '[2]',
      'Zodiac',
    ]);
  });

  it('places by the server sort title, not the title, when the film has one', () => {
    // An import can give "Zulu" the sort title "aardvark": the server lists it first, so the tile follows it.
    const byTitle = [
      { ...f(1, 'Zulu', 1964), sortTitle: 'aardvark' },
      f(2, 'Heat', 1995),
      f(3, 'The Matrix', 1999, 2),
    ];
    expect(labels(groupFilms(byTitle, COLLECTIONS, 'title'))).toEqual(['Zulu', 'Heat', '[2]']);
  });

  it('keeps a title tie stable: the film first, then the tile', () => {
    const items = groupFilms(
      [f(1, 'Alien', 1979), f(2, 'Aliens', 1986, 1), f(3, 'Alien 3', 1992, 1)],
      COLLECTIONS,
      'title',
    );
    expect(labels(items)).toEqual(['Alien', '[1]']);
  });

  it('puts tiles after the last film when their names sort last, and handles only tiles', () => {
    const items = groupFilms(
      [f(1, 'Aardvark', 2000), f(2, 'The Matrix', 1999, 2)],
      COLLECTIONS,
      'title',
    );
    expect(labels(items)).toEqual(['Aardvark', '[2]']);
    expect(labels(groupFilms([f(2, 'The Matrix', 1999, 2)], COLLECTIONS, 'title'))).toEqual([
      '[2]',
    ]);
  });

  it('counts only the films that came through the filters, and makes no tile for a collection with none', () => {
    const filtered = [f(13, 'Alien', 1979, 1), f(10, 'Heat', 1995)];
    const items = groupFilms(filtered, COLLECTIONS, 'added');
    expect(labels(items)).toEqual(['[1]', 'Heat']);
    expect(items[0].count).toBe(1);
    expect(items.some((i) => i.type === 'collection' && i.collection.id === 2)).toBe(false);
  });

  it('leaves a film whose collection is not in the list as a plain film, and with no collections groups nothing', () => {
    const films = [f(1, 'Orphan', 2000, 99), f(2, 'Heat', 1995)];
    expect(labels(groupFilms(films, COLLECTIONS, 'added'))).toEqual(['Orphan', 'Heat']);
    expect(labels(groupFilms(added, [], 'title'))).toHaveLength(added.length);
  });
});
