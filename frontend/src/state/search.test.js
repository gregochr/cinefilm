import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { buildFixtureSearchIndex } from '../api/fixtureSearchIndex.js';
import {
  DEFAULT_CAPS,
  matchEntry,
  matchesAnyName,
  nearestPrefix,
  normalise,
  queryTerms,
  search,
  withNorm,
} from './search.js';
import { prepareIndex } from './searchIndex.js';

// Vitest runs in frontend/, so the repository root is one level up.
const vectors = JSON.parse(
  readFileSync(resolve(process.cwd(), '../docs/design/search/vectors.json'), 'utf8'),
);

const film = (id, title, extra = {}) => ({ id, t: title, ...extra });
const person = (id, n, a, d = 0) => ({ id, n, a, d });
const collection = (id, n) => ({ id, n, o: 2, of: 3, p: [] });
const prep = (raw) => prepareIndex({ films: [], collections: [], people: [], ...raw });
const titles = (result, key = 'titles') =>
  (result.groups.find((g) => g.key === key)?.items ?? []).map((i) => i.title ?? i.name);
const entry = (name) => withNorm({ id: 1, title: name });
const hit = (name, query) => matchEntry(entry(name), queryTerms(query));
const marked = (name, query) => hit(name, query).ranges.map((r) => name.slice(r.start, r.end));

describe('normalise', () => {
  it('folds case, accents, apostrophes and punctuation', () => {
    expect(normalise('Amélie').text).toBe('amelie');
    expect(normalise("O'Connor").text).toBe('oconnor');
    expect(normalise('O’Connor').text).toBe('oconnor');
    expect(normalise('‘Allo').text).toBe('allo');
    expect(normalise('WALL·E').words).toEqual([
      { text: 'wall', start: 0 },
      { text: 'e', start: 5 },
    ]);
    expect(normalise('  Spider-Man:  Homecoming ').text).toBe('spider man homecoming');
    expect(normalise('Chloé Zhao').words.map((w) => w.text)).toEqual(['chloe', 'zhao']);
    expect(normalise('...').words).toEqual([]);
    expect(normalise(null).text).toBe('');
  });

  it('maps every folded character back to its place in the original', () => {
    const { text, map, ends } = normalise('Chloé Zhao');
    expect(text).toBe('chloe zhao');
    expect(map).toHaveLength(text.length);
    expect(map[4]).toBe(4);
    expect(map[6]).toBe(6);
    expect(ends[4]).toBe(5);
    const apostrophe = normalise("O'Connor");
    expect(apostrophe.map).toEqual([0, 2, 3, 4, 5, 6, 7]);
  });

  it('keeps a combining mark inside the letter it follows, and ignores one with no letter', () => {
    const decomposed = 'Amélie';
    const { text, map, ends } = normalise(decomposed);
    expect(text).toBe('amelie');
    expect(decomposed.slice(map[2], ends[2])).toBe('é');
    expect(normalise('́ a').text).toBe('a');
  });

  it('counts astral characters as one source character', () => {
    const { text, map, ends } = normalise('a\u{1F600}b \u{1F600}c');
    expect(text).toBe('a b c');
    expect(map[2]).toBe(3);
    expect(ends[2]).toBe(4);
    expect(map[4]).toBe(7);
  });
});

describe('characters outside the BMP and final sigma', () => {
  it('keeps highlight ranges on the right characters after a surrogate pair', () => {
    const name = '𠮷田 太郎';
    const { text, map } = normalise(name);
    expect(map).toHaveLength(text.length);
    expect(marked(name, '太')).toEqual(['太']);
    expect(marked(name, '𠮷')).toEqual(['𠮷']);
    expect(marked(name, '𠮷田 太')).toEqual(['𠮷田', '太']);
  });

  it('matches a typed final sigma exactly against an upper-case name', () => {
    const index = prep({ people: [person(1, 'ΣΩΚΡΑΤΗΣ', 3)] });
    const result = search(index, 'σωκρατης');
    expect(result.fuzzy).toBe(false);
    expect(titles(result, 'actors')).toEqual(['ΣΩΚΡΑΤΗΣ']);
  });
});

describe('matchEntry', () => {
  it('needs every term to prefix some word, in any order, each word once', () => {
    expect(hit('Star Wars', 'star wa')).not.toBeNull();
    expect(hit('Star Wars', 'wa star')).not.toBeNull();
    expect(hit('Star Wars', 'star star')).toBeNull();
    expect(hit('Hancock', 'ha ha')).toBeNull();
    expect(hit('Ha Ha Hancock', 'ha ha')).not.toBeNull();
    expect(hit('Hang Hero', 'h hang')).not.toBeNull();
    expect(hit('Hang', 'hang h')).toBeNull();
    expect(matchEntry(entry('Heat'), [])).toBeNull();
  });

  it('rejects a match inside a word', () => {
    expect(hit('Twisters', 'w')).toBeNull();
    expect(hit('Twisters', 'tw')).not.toBeNull();
    expect(hit('Spider-Man', 'spiderman')).toBeNull();
  });

  it('matches through accents and apostrophes', () => {
    expect(hit('Amélie', 'amelie')).not.toBeNull();
    expect(hit('Amélie', 'AMÉLI')).not.toBeNull();
    expect(hit("Gavin O'Connor", 'ocon')).not.toBeNull();
    expect(hit('Gavin O’Connor', 'ocon')).not.toBeNull();
    expect(hit("Gavin O'Connor", 'connor')).toBeNull();
  });

  it('accepts entries that were not prepared, using their title or name', () => {
    expect(matchEntry({ title: 'Heat' }, ['he'])).not.toBeNull();
    expect(matchEntry({ name: 'Tom Hanks' }, ['han'])).not.toBeNull();
    expect(matchEntry({}, ['a'])).toBeNull();
  });

  it('is tier 0 at the first word and tier 1 on a later one', () => {
    expect(hit('Star Wars', 'sta').tier).toBe(0);
    expect(hit('Star Wars', 'wa').tier).toBe(1);
    expect(hit('Star Wars', 'wa star').tier).toBe(1);
    expect(hit('Star Wars', 'star wa').tier).toBe(0);
  });

  it('ignores a leading "the" for the tier, unless the query starts with it', () => {
    expect(hit('The Hangover', 'ha').tier).toBe(0);
    expect(hit('The Hangover', 'han the').tier).toBe(0);
    expect(hit('The Hangover', 'the').tier).toBe(0);
    expect(hit('The Hangover', 'the han').tier).toBe(0);
    expect(hit('The Hangover', 'hangover').ranges).toHaveLength(1);
    expect(hit('Other The', 'the').tier).toBe(1);
    expect(hit('The', 'the').tier).toBe(0);
    expect(hit('The Hangover Ha', 'ha ha').tier).toBe(0);
    expect(hit('The Hangover Ha', 'ha han').tier).toBe(1);
  });

  it('maps the highlight ranges back onto the original name', () => {
    expect(marked('Star Wars', 'star wa')).toEqual(['Star', 'Wa']);
    expect(marked('Amélie', 'amel')).toEqual(['Amél']);
    expect(marked("Gavin O'Connor", 'ocon')).toEqual(["O'Con"]);
    expect(marked('Gavin O’Connor', 'gav ocon')).toEqual(['Gav', 'O’Con']);
    expect(marked('WALL·E', 'wall e')).toEqual(['WALL', 'E']);
    expect(marked('Chloé Zhao', 'chloe z')).toEqual(['Chloé', 'Z']);
    expect(marked('Spider-Man', 'man')).toEqual(['Man']);
    expect(marked('Amélie', 'ame')).toEqual(['Amé']);
  });
});

describe('matchesAnyName', () => {
  it('is word-prefix over any of the names, and true for an empty query', () => {
    expect(matchesAnyName('mann', ['Heat', 'Michael Mann'])).toBe(true);
    expect(matchesAnyName('ann', ['Heat', 'Michael Mann'])).toBe(false);
    expect(matchesAnyName('', ['Heat'])).toBe(true);
    expect(matchesAnyName('he', [undefined, null, 'Heat'])).toBe(true);
  });
});

describe('the shared vectors (docs/design/search/vectors.json)', () => {
  it.each(vectors.map((v) => [v.query, v.name, v.matches]))(
    '%j against %j is %s',
    (query, name, matches) => {
      const terms = queryTerms(query);
      expect(terms.length > 0 && matchEntry(entry(name), terms) !== null).toBe(matches);
    },
  );
});

describe('ranking', () => {
  it('puts a match on the first word before one on a later word', () => {
    const index = prep({
      films: [film(1, 'Star Wars'), film(2, 'Wars of the Roses'), film(3, 'A Warm Day')],
    });
    expect(titles(search(index, 'war'))).toEqual(['Wars of the Roses', 'A Warm Day', 'Star Wars']);
  });

  it('ignores "The" for tier and sort', () => {
    const index = prep({
      films: [film(1, 'Zed Hat'), film(2, 'The Hangover'), film(3, 'Hancock')],
    });
    const result = search(index, 'ha');
    expect(titles(result)).toEqual(['Hancock', 'The Hangover', 'Zed Hat']);
    expect(result.groups[0].items.map((i) => i.tier)).toEqual([0, 0, 1]);
  });

  it('puts in-progress films first, then the most recently played, then alphabetical', () => {
    const index = prep({
      films: [
        film(1, 'Hab'),
        film(2, 'Hac', { at: '2026-01-01T10:00:00Z' }),
        film(3, 'Had', { at: '2026-06-01T10:00:00Z' }),
        film(4, 'Hae', { left: 5000, at: '2025-01-01T10:00:00Z' }),
        film(5, 'Haa'),
        film(6, 'Haf', { left: 9000 }),
      ],
    });
    expect(titles(search(index, 'ha', { caps: { titles: 10 } }))).toEqual([
      'Hae',
      'Haf',
      'Had',
      'Hac',
      'Haa',
      'Hab',
    ]);
  });

  it('breaks the last tie by id, and orders collections by name without "The"', () => {
    const index = prep({
      films: [film(9, 'Dune', { s: 'dune' }), film(2, 'Dune')],
      collections: [collection(1, 'The Hangover Collection'), collection(2, 'Hancock Collection')],
    });
    expect(search(index, 'dune').groups[0].items.map((i) => i.id)).toEqual([2, 9]);
    expect(titles(search(index, 'han'), 'collections')).toEqual(['Hancock', 'The Hangover']);
  });

  it('orders people by film count in that role, then name', () => {
    const index = prep({
      people: [
        person(1, 'Ha Zed', 1, 5),
        person(2, 'Ha Abe', 1, 2),
        person(3, 'Ha Bea', 7, 0),
        person(4, 'Ha Cid', 3, 5),
        person(5, 'Ha Amy', 3, 0),
        person(6, 'Ha Ann', 3, 0),
      ],
    });
    const result = search(index, 'ha', { caps: { actors: 10, directors: 10 } });
    expect(titles(result, 'actors')).toEqual([
      'Ha Bea',
      'Ha Amy',
      'Ha Ann',
      'Ha Cid',
      'Ha Abe',
      'Ha Zed',
    ]);
    expect(titles(result, 'directors')).toEqual(['Ha Cid', 'Ha Zed', 'Ha Abe']);
  });
});

describe('groups and caps', () => {
  const many = prep({
    films: Array.from({ length: 7 }, (_, i) => film(i + 1, `Alpha ${i}`)),
    collections: [
      collection(1, 'Alpha One'),
      collection(2, 'Alpha Two'),
      collection(3, 'Alpha Three'),
    ],
    people: [
      person(1, 'Alpha A', 5, 1),
      person(2, 'Alpha B', 4, 0),
      person(3, 'Alpha C', 3, 0),
      person(4, 'Alpha D', 2, 0),
      person(5, 'Alpha E', 0, 3),
      person(6, 'Alpha F', 0, 2),
      person(7, 'Alpha G', 0, 1),
      person(8, 'Alpha H', 0, 1),
    ],
  });

  it('lists the groups in order with labels, caps and the totals before the cap', () => {
    const result = search(many, 'alpha');
    expect(result.query).toBe('alpha');
    expect(result.fuzzy).toBe(false);
    expect(result.groups.map((g) => [g.key, g.label, g.items.length, g.total])).toEqual([
      ['titles', 'Titles', 5, 7],
      ['collections', 'Collections', 2, 3],
      ['actors', 'Actors', 3, 4],
      ['directors', 'Directors', 3, 5],
    ]);
    expect(result.total).toBe(7 + 3 + 4 + 5);
    expect(DEFAULT_CAPS).toEqual({ titles: 5, collections: 2, actors: 3, directors: 3 });
  });

  it('lets the caps be overridden', () => {
    const result = search(many, 'alpha', { caps: { titles: 1, collections: 0 } });
    expect(result.groups[0].items).toHaveLength(1);
    expect(result.groups[1].items).toHaveLength(0);
    expect(result.groups[1].total).toBe(3);
    expect(result.groups[2].items).toHaveLength(3);
  });

  it('shows a person who acts and directs in both groups', () => {
    const result = search(many, 'alpha a');
    expect(result.groups.map((g) => g.key)).toEqual(['actors', 'directors']);
    expect(result.groups[0].items[0].name).toBe('Alpha A');
    expect(result.groups[1].items[0].name).toBe('Alpha A');
  });

  it('leaves out empty groups', () => {
    const result = search(many, 'alpha one');
    expect(result.groups.map((g) => g.key)).toEqual(['collections']);
    expect(result.total).toBe(1);
  });

  it('carries the highlight ranges on each item', () => {
    const [{ items }] = search(many, 'alp').groups;
    expect(items[0].ranges).toEqual([{ start: 0, end: 3 }]);
  });

  it('returns nothing for an empty query or no index', () => {
    const none = { groups: [], total: 0, fuzzy: false };
    expect(search(many, '')).toMatchObject(none);
    expect(search(many, '   ')).toMatchObject(none);
    expect(search(many, '...')).toMatchObject(none);
    expect(search(many, undefined)).toMatchObject({ ...none, query: '' });
    expect(search(null, 'alpha')).toMatchObject(none);
  });
});

describe('typo distances', () => {
  const index = prep({ films: [film(1, 'Warrior'), film(2, 'Alien'), film(3, 'Hancock Frost')] });
  const found = (q) => search(index, q).total > 0;

  it('measures each operation type', () => {
    expect(nearestPrefix('warripr', 'warrior', 2)).toEqual({ distance: 1, length: 7 });
    expect(nearestPrefix('wario', 'warrior', 2).distance).toBe(1);
    expect(nearestPrefix('warrrior', 'warrior', 2).distance).toBe(1);
    expect(nearestPrefix('wrarior', 'warrior', 2).distance).toBe(1);
    expect(nearestPrefix('warrior', 'warrior', 2)).toEqual({ distance: 0, length: 7 });
    expect(nearestPrefix('zzzz', 'warrior', 1).distance).toBe(Infinity);
    expect(nearestPrefix('warriors', 'war', 1).distance).toBe(Infinity);
  });

  it('finds Warrior for a substitution, insertion, deletion and transposition', () => {
    for (const q of ['warripr', 'warrrior', 'warrir', 'wraror'.replace('wraror', 'wrarior')]) {
      const result = search(index, q);
      expect(titles(result), q).toEqual(['Warrior']);
      expect(result.fuzzy, q).toBe(true);
    }
  });

  it('allows 1 at 4 to 6 characters and 2 at 7 or more', () => {
    expect(found('alein')).toBe(true); // 5 characters, one transposition
    expect(found('aliwn')).toBe(true); // one substitution
    expect(found('alxxn')).toBe(false); // two
    expect(found('alin')).toBe(true); // 4 characters, one deletion
    expect(found('blie')).toBe(true); // 4 characters, one substitution
    expect(found('bxie')).toBe(false); // 4 characters, two
    expect(found('wariro')).toBe(true); // 6 characters, one
    expect(found('wxrxor')).toBe(false); // 6 characters, two
    expect(found('wxrrixr')).toBe(true); // 7 characters, two
    expect(found('wxrrxxr')).toBe(false); // 7 characters, three
    expect(found('wxrrxxr x')).toBe(false);
  });

  it('measures the allowance on the whole query, and sums the terms', () => {
    expect(found('hancok frost')).toBe(true); // 11 characters, one error
    expect(found('hancok frozt')).toBe(true); // two errors, allowed at 11
    expect(found('hanxok frozt')).toBe(false); // three
    expect(found('hancok zzzz')).toBe(false); // a term with no word
  });

  it('ranks by summed distance first', () => {
    const pair = prep({ films: [film(1, 'Wareyor'), film(2, 'Warrior')] });
    expect(titles(search(pair, 'warrxor'))).toEqual(['Warrior', 'Wareyor']);
  });

  it('highlights the word prefix it matched', () => {
    const [item] = search(index, 'warripr').groups[0].items;
    expect(item.ranges).toEqual([{ start: 0, end: 7 }]);
    const [lead] = search(prep({ films: [film(1, 'The Hangvoer')] }), 'hangover').groups[0].items;
    expect(lead.tier).toBe(0);
    const [later] = search(prep({ films: [film(1, 'Big Hangvoer')] }), 'hangover').groups[0].items;
    expect(later.tier).toBe(1);
  });

  it('finds people and collections too', () => {
    const wide = prep({
      collections: [collection(1, 'Star Wars Collection')],
      people: [person(1, 'Denzel Washington', 6)],
    });
    expect(titles(search(wide, 'washingotn'), 'actors')).toEqual(['Denzel Washington']);
    expect(titles(search(wide, 'starr wars'), 'collections')).toEqual(['Star Wars']);
  });
});

describe('the fallback', () => {
  const index = prepareIndex(buildFixtureSearchIndex());

  it('never fires when any group has an exact hit', () => {
    const result = search(
      prep({ films: [film(1, 'Warrior')], people: [person(1, 'Warrick Foo', 3)] }),
      'warri',
    );
    expect(result.fuzzy).toBe(false);
    expect(result.total).toBe(2);
    const onlyPeople = search(
      prep({ films: [film(1, 'Zebra')], people: [person(1, 'Warrick Foo', 3)] }),
      'warri',
    );
    expect(onlyPeople.fuzzy).toBe(false);
    expect(onlyPeople.groups.map((g) => g.key)).toEqual(['actors']);
  });

  it('never fires under four characters', () => {
    expect(search(index, 'zq')).toMatchObject({ total: 0, fuzzy: false });
    expect(search(index, 'wra')).toMatchObject({ total: 0, fuzzy: false });
    expect(search(index, 'w r a')).toMatchObject({ total: 0, fuzzy: false });
  });

  it('does not go fuzzy for "warr", and does not pull in Wars', () => {
    const result = search(index, 'warr');
    expect(result.fuzzy).toBe(false);
    expect(titles(result)).toEqual(['Warrior']);
  });

  it('reports nothing when nothing is close', () => {
    expect(search(index, 'qqqqqqq')).toMatchObject({ total: 0, groups: [], fuzzy: false });
  });
});

describe('the fixture index narrowing', () => {
  const index = prepareIndex(buildFixtureSearchIndex());
  const all = (q) =>
    search(index, q, { caps: { titles: 99, collections: 99, actors: 99, directors: 99 } });

  it('w, wa, war, warr ends on Warrior alone', () => {
    const steps = ['w', 'wa', 'war', 'warr'].map((q) => search(index, q));
    expect(steps[0].total).toBeGreaterThan(steps[1].total);
    expect(steps[1].total).toBeGreaterThan(steps[3].total);
    expect(steps[3].groups.map((g) => g.key)).toEqual(['titles']);
    expect(steps[3].total).toBe(1);
    expect(titles(steps[3])).toEqual(['Warrior']);
    expect(steps.every((s) => !s.fuzzy)).toBe(true);
  });

  it('"w" does not match Twisters-style mid-word hits', () => {
    for (const group of all('w').groups) {
      for (const item of group.items) {
        const name = item.title ?? item.name;
        expect(
          name
            .toLowerCase()
            .split(/[^a-z0-9]+/)
            .some((w) => w.startsWith('w')),
          name,
        ).toBe(true);
      }
    }
  });

  it('"ha" finds Hancock, The Hangover and the Harry Potters; both collections; Tom Hanks', () => {
    const result = all('ha');
    expect(titles(result)).toEqual(
      expect.arrayContaining([
        'Hancock',
        'The Hangover',
        'Harry Potter and the Philosopher’s Stone',
        'Harry Potter and the Chamber of Secrets',
      ]),
    );
    expect(result.groups[0].items.find((i) => i.title === 'The Hangover').tier).toBe(0);
    expect(titles(result, 'collections')).toEqual(
      expect.arrayContaining(['The Hangover', 'Harry Potter']),
    );
    expect(titles(result, 'actors')).toContain('Tom Hanks');
    expect(titles(search(index, 'ha'), 'actors')[0]).toBe('Tom Hanks');
  });

  it('"warripr" finds Warrior with fuzzy set', () => {
    const result = search(index, 'warripr');
    expect(result.fuzzy).toBe(true);
    expect(titles(result)).toEqual(['Warrior']);
  });

  it('finds "ocon" as the director Gavin O\'Connor', () => {
    expect(titles(search(index, 'ocon'), 'directors')).toEqual(["Gavin O'Connor"]);
  });
});

// The aim is 16 ms (one frame); coverage instrumentation on a loaded CI box roughly doubles it, so the guard is 30.
const BOUND_MS = 30;

describe('speed', () => {
  const syllables = ['ba', 'ko', 'ri', 'tu', 'me', 'sa', 'lo', 'vi', 'ne', 'du', 'pa', 'ge'];
  let seed = 7;
  const rand = (n) => {
    seed = (seed * 1103515245 + 12345) % 2147483648;
    return seed % n;
  };
  const word = () =>
    Array.from({ length: 2 + rand(3) }, () => syllables[rand(syllables.length)]).join('');
  const name = () => Array.from({ length: 1 + rand(3) }, word).join(' ');
  const raw = {
    films: [
      ...Array.from({ length: 1100 }, (_, i) => ({ id: i + 1, t: name(), y: 2000, d: [name()] })),
      { id: 5000, t: 'Warrior', y: 2011, d: [] },
    ],
    collections: Array.from({ length: 80 }, (_, i) => collection(i + 1, `${name()} Collection`)),
    people: Array.from({ length: 5000 }, (_, i) => person(i + 1, name(), rand(9), rand(3))),
  };
  const index = prepareIndex(raw);

  const median = (query) => {
    const times = Array.from({ length: 5 }, () => {
      const start = performance.now();
      search(index, query);
      return performance.now() - start;
    }).sort((a, b) => a - b);
    return times[2];
  };

  it('answers an exact query in under 30 ms (median of 5)', () => {
    const time = median('ba');
    console.log(`search perf: exact ${time.toFixed(2)} ms`);
    expect(search(index, 'ba').total).toBeGreaterThan(0);
    expect(time).toBeLessThan(BOUND_MS);
  });

  it('answers a fuzzy query in under 30 ms (median of 5)', () => {
    const time = median('warripr');
    console.log(`search perf: fuzzy ${time.toFixed(2)} ms`);
    expect(search(index, 'warripr').fuzzy).toBe(true);
    expect(time).toBeLessThan(BOUND_MS);
  });
});
