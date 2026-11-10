import { describe, expect, it } from 'vitest';
import { buildFixtureSearchIndex } from './fixtureSearchIndex.js';

const index = buildFixtureSearchIndex();
const film = (title) => index.films.find((f) => f.t === title);

describe('the fixture search index', () => {
  it('carries the fixture films, with time left for the part-watched ones', () => {
    expect(film('Heat')).toEqual({
      id: 1,
      t: 'Heat',
      y: 1995,
      d: ['Michael Mann'],
      left: 10200000 - 3871000,
      s: 'heat',
    });
    expect(film('Blade Runner').left).toBeUndefined();
  });

  it('has the sample films the search checks need', () => {
    for (const title of [
      'Walk the Line',
      'WALL·E',
      'War Horse',
      'War of the Worlds',
      'Warrior',
      'Hancock',
      'The Hangover',
    ]) {
      expect(film(title), title).toBeDefined();
    }
    expect(index.films.filter((f) => f.t.startsWith('Harry Potter'))).toHaveLength(2);
  });

  it('has Warrior in progress with 2 h 3 min left and The Hangover played, not in progress', () => {
    expect(film('Warrior')).toMatchObject({
      y: 2011,
      d: ["Gavin O'Connor"],
      left: 123 * 60000,
      at: '2026-11-08T20:00:00Z',
      s: 'warrior',
    });
    expect(film('The Hangover').left).toBeUndefined();
    expect(film('The Hangover').at).toBe('2026-10-02T21:00:00Z');
    expect(film('The Hangover').s).toBe('hangover');
  });

  it('has the Star Wars, Harry Potter and The Hangover collections with at most two posters', () => {
    const names = index.collections.map((c) => c.n);
    expect(names).toEqual(
      expect.arrayContaining([
        'Star Wars Collection',
        'Harry Potter Collection',
        'The Hangover Collection',
      ]),
    );
    const harry = index.collections.find((c) => c.n === 'Harry Potter Collection');
    expect(harry).toEqual({
      id: 4,
      n: 'Harry Potter Collection',
      o: 7,
      of: 8,
      p: ['/hp1.jpg', '/hp2.jpg'],
    });
    expect(index.collections.every((c) => c.p.length <= 2)).toBe(true);
    expect(index.collections.find((c) => c.n === 'Alien').p).toEqual([]);
  });

  it('has the sample people with their roles, plus the fixture directors', () => {
    const person = (n) => index.people.find((p) => p.n === n);
    expect(person('Tom Hanks')).toMatchObject({ a: 9, d: 0 });
    for (const name of ['Christoph Waltz', 'Denzel Washington', 'Emma Watson']) {
      expect(person(name).a).toBeGreaterThan(0);
    }
    expect(person("Gavin O'Connor")).toMatchObject({ a: 0, d: 1 });
    expect(person('Michael Mann')).toMatchObject({ a: 0, d: 1 });
    expect(new Set(index.people.map((p) => p.id)).size).toBe(index.people.length);
  });
});
