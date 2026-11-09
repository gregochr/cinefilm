import { describe, expect, it } from 'vitest';
import { browseCollections, displayName, fanPosters, isComplete, sortKey } from './collections.js';

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
