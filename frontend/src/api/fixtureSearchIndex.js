// Dev-only: the compact search index (the shape of GET /api/search-index) for the fixture server, built from the
// existing fixtures plus the sample entries the search design needs.
import films from '../test/fixtures/films.json';
import collections from '../test/fixtures/collections.json';

const MINUTE = 60000;

// Sample films beyond the fixture list: id, title, year, directors, poster, minutes left (in progress), last played.
const EXTRA_FILMS = [
  [200, 'Walk the Line', 2005, ['James Mangold'], '/walk.jpg'],
  [201, 'WALL·E', 2008, ['Andrew Stanton'], '/walle.jpg'],
  [202, 'War Horse', 2011, ['Steven Spielberg'], '/warhorse.jpg'],
  [203, 'War of the Worlds', 2005, ['Steven Spielberg'], '/wotw.jpg'],
  [204, 'Warrior', 2011, ["Gavin O'Connor"], '/warrior.jpg', 123, '2026-11-08T20:00:00Z'],
  [205, 'Hancock', 2008, ['Peter Berg'], '/hancock.jpg'],
  [206, 'The Hangover', 2009, ['Todd Phillips'], '/hangover.jpg', null, '2026-10-02T21:00:00Z'],
  [207, 'Harry Potter and the Philosopher’s Stone', 2001, ['Chris Columbus'], '/hp1.jpg'],
  [208, 'Harry Potter and the Chamber of Secrets', 2002, ['Chris Columbus'], '/hp2.jpg'],
  [209, 'Star Wars', 1977, ['George Lucas'], '/sw4.jpg'],
  [210, 'The Empire Strikes Back', 1980, ['Irvin Kershner'], '/sw5.jpg'],
  [211, 'The Hangover Part II', 2011, ['Todd Phillips'], '/hangover2.jpg'],
];

const EXTRA_COLLECTIONS = [
  [40, 'Star Wars Collection', 2, 9, ['/sw4.jpg', '/sw5.jpg']],
  [42, 'The Hangover Collection', 2, 3, ['/hangover.jpg', '/hangover2.jpg']],
];

// Sample people: id, name, films as an actor, films as a director.
const EXTRA_PEOPLE = [
  [300, 'Tom Hanks', 9, 0],
  [301, 'Christoph Waltz', 4, 0],
  [302, 'Denzel Washington', 6, 0],
  [303, 'Emma Watson', 5, 0],
  [304, "Gavin O'Connor", 0, 1],
  [305, 'Peter Berg', 1, 3],
  [306, 'Todd Phillips', 0, 2],
];

const slug = (text) => text.toLowerCase().replace(/^the\s+/, '');

function fixtureFilm(card) {
  const entry = {
    id: card.id,
    t: card.title,
    y: card.year,
    d: card.director ? [card.director] : [],
  };
  if (card.posterPath) entry.p = card.posterPath;
  if (card.progress) entry.left = card.progress.runtimeMs - card.progress.positionMs;
  entry.s = card.sortTitle;
  return entry;
}

function extraFilm([id, title, year, directors, poster, minutesLeft, playedAt]) {
  const entry = { id, t: title, y: year, d: directors, p: poster };
  if (minutesLeft) entry.left = minutesLeft * MINUTE;
  if (playedAt) entry.at = playedAt;
  entry.s = slug(title);
  return entry;
}

/** The index the fixture server returns: fixture films and collections, then the sample extras. */
export function buildFixtureSearchIndex() {
  const directors = [...new Set(films.films.map((f) => f.director).filter(Boolean))];
  return {
    films: [...films.films.map(fixtureFilm), ...EXTRA_FILMS.map(extraFilm)],
    collections: [
      ...collections.collections.map((c) => ({
        id: c.id,
        n: c.name,
        o: c.ownedCount,
        of: c.totalCount,
        p: c.posterPaths.filter(Boolean).slice(0, 2),
      })),
      ...EXTRA_COLLECTIONS.map(([id, n, o, of, p]) => ({ id, n, o, of, p })),
    ],
    people: [
      ...directors.map((n, i) => ({ id: 400 + i, n, a: 0, d: 1 })),
      ...EXTRA_PEOPLE.map(([id, n, a, d]) => ({ id, n, a, d })),
    ],
  };
}
