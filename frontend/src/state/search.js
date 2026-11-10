// Header-search matching, ranking and the typo fallback. Pure functions over the prepared index: no React, no DOM.
//
// Normalisation is mirrored on the server (`SearchText.java`, used by the Library search); the shared cases are in
// `docs/design/search/vectors.json`, which both sides test against.
import { sortKey } from './collections.js';

const MARK = /\p{M}/u;
const KEEP = /[\p{L}\p{N}]/u;
const APOSTROPHES = new Set(["'", '’', '‘']);

/** A bit per letter (by code modulo 32) present in `word`: a cheap way to rule out a typo match. */
function letterMask(word) {
  let mask = 0;
  for (let i = 0; i < word.length; i++) mask |= 1 << (word.charCodeAt(i) & 31);
  return mask;
}

/** How many of the term's letter-bits the word lacks: each needs at least one edit, so more than `limit` rules it out. */
function missingBits(termMask, wordMask) {
  let bits = termMask & ~wordMask;
  let count = 0;
  while (bits) {
    bits &= bits - 1;
    count++;
  }
  return count;
}

/**
 * Folds `s` for matching: NFD, combining marks dropped, lower-case, apostrophes removed, every other
 * non-letter/digit a single space. Returns:
 *  - `text`: the folded string;
 *  - `words`: `{ text, start }` for each word, `start` an offset into `text`;
 *  - `map[i]` / `ends[i]`: where `text[i]` came from in `s`, as the start and end of that source character, so a
 *    range on `text` becomes a range on the original string even where folding changed the length.
 */
export function normalise(s) {
  const source = String(s ?? '');
  let text = '';
  const map = [];
  const ends = [];
  let gap = false;
  let gapAt = 0;
  let index = 0;
  const push = (ch, from, to) => {
    if (gap && text.length > 0) {
      text += ' ';
      map.push(gapAt);
      ends.push(gapAt + 1);
    }
    gap = false;
    text += ch;
    map.push(from);
    ends.push(to);
  };
  for (const cp of source) {
    const from = index;
    index += cp.length;
    for (const decomposed of cp.normalize('NFD')) {
      if (MARK.test(decomposed)) {
        // A dropped mark belongs to the letter before it, so a highlight of that letter covers it.
        if (!gap && ends.length > 0) ends[ends.length - 1] = index;
        continue;
      }
      for (const ch of decomposed.toLowerCase()) {
        if (APOSTROPHES.has(ch)) continue;
        if (KEEP.test(ch)) {
          push(ch, from, index);
        } else {
          if (!gap) gapAt = from;
          gap = true;
        }
      }
    }
  }
  const words = [];
  for (const found of text.matchAll(/[^ ]+/g)) words.push({ text: found[0], start: found.index });
  return { text, words, map, ends, masks: words.map((w) => letterMask(w.text)) };
}

/** The words of a query, normalised. */
export function queryTerms(query) {
  return normalise(query).words.map((w) => w.text);
}

const nameOf = (entry) => entry.title ?? entry.name ?? '';
const normOf = (entry) => entry.norm ?? normalise(nameOf(entry));

/** `entry` with its normalised name attached; done once when the index loads. */
export function withNorm(entry) {
  return { ...entry, norm: normalise(nameOf(entry)) };
}

/** Prepares an index (see `prepareIndex`): every entry carries its normalised name. */
export function normaliseIndex(index) {
  return {
    films: index.films.map(withNorm),
    collections: index.collections.map(withNorm),
    people: index.people.map(withNorm),
  };
}

/** Gives each term a different word it prefixes (backtracking over a handful of words); fills `out` with the words. */
function assign(terms, at, words, used, out) {
  if (at === terms.length) return true;
  for (let i = 0; i < words.length; i++) {
    if (!used[i] && words[i].text.startsWith(terms[at])) {
      used[i] = true;
      out[at] = i;
      if (assign(terms, at + 1, words, used, out)) return true;
      used[i] = false;
    }
  }
  return false;
}

/** The index of the word that counts as "first": the second when the name begins "the" (unless the query does). */
function leadWord(words, terms) {
  return words.length > 1 && words[0].text === 'the' && terms[0] !== 'the' ? 1 : 0;
}

/** `{ tier, words }` (the word each term used, by index) or null. */
function assignEntry(norm, terms) {
  const words = norm.words;
  if (terms.length === 0 || words.length < terms.length) return null;
  const lead = leadWord(words, terms);
  if (terms.length === 1) {
    // The common case, kept free of allocation: most keystrokes test thousands of names.
    if (words[lead].text.startsWith(terms[0])) return { tier: 0, words: [lead] };
    for (let i = 0; i < words.length; i++) {
      if (words[i].text.startsWith(terms[0])) return { tier: 1, words: [i] };
    }
    return null;
  }
  if (words[lead].text.startsWith(terms[0])) {
    const used = words.map((_, i) => i === lead);
    const rest = [];
    if (assign(terms.slice(1), 0, words, used, rest)) return { tier: 0, words: [lead, ...rest] };
  }
  const out = [];
  return assign(
    terms,
    0,
    words,
    words.map(() => false),
    out,
  )
    ? { tier: 1, words: out }
    : null;
}

/** Source ranges `{ start, end }` on the original name for the given word indices (each with its prefix length). */
function rangesFor(norm, spans) {
  return spans
    .map(({ word, length }) => {
      const first = norm.words[word].start;
      return { start: norm.map[first], end: norm.ends[first + length - 1] };
    })
    .sort((a, b) => a.start - b.start);
}

/**
 * Matches one prepared entry: every term must prefix a word, each word used by one term only. Returns
 * `null`, or `{ tier, ranges }`: tier 0 when the first term matches the name's first word (a leading "the" does not
 * count, unless the query starts with it), tier 1 otherwise; `ranges` are the matched prefixes on the original name.
 */
export function matchEntry(entry, terms) {
  const norm = normOf(entry);
  const found = assignEntry(norm, terms);
  if (!found) return null;
  return {
    tier: found.tier,
    ranges: rangesFor(
      norm,
      found.words.map((word, i) => ({ word, length: terms[i].length })),
    ),
  };
}

/** True when `query` matches any of `names` (word-prefix, as the header search does). For the fixture server. */
export function matchesAnyName(query, names) {
  const terms = queryTerms(query);
  if (terms.length === 0) return true;
  return names.some((name) => name && assignEntry(normalise(name), terms) !== null);
}

// Three rows of the distance table, reused by every call: a keystroke runs thousands of them.
let rows = [new Int32Array(64), new Int32Array(64), new Int32Array(64)];
let nearestLength = 0;

/**
 * Damerau-Levenshtein (optimal string alignment) distance from `term` to the nearest prefix of `word` whose length
 * is within `limit` of the term's. Returns that distance (`Infinity` when it is above `limit`) and leaves the
 * prefix length in `nearestLength`. Stops early when two rows in a row are all above `limit`.
 */
function nearestPrefixDistance(term, word, limit) {
  const m = term.length;
  const n = Math.min(word.length, m + limit);
  if (n < m - limit) return Infinity;
  if (rows[0].length <= n) rows = rows.map(() => new Int32Array(n + 1));
  let [older, previous, row] = rows;
  for (let j = 0; j <= n; j++) previous[j] = j;
  let previousMin = 0;
  for (let i = 1; i <= m; i++) {
    row[0] = i;
    let rowMin = i;
    const t = term.charCodeAt(i - 1);
    for (let j = 1; j <= n; j++) {
      const w = word.charCodeAt(j - 1);
      let d = Math.min(previous[j] + 1, row[j - 1] + 1, previous[j - 1] + (t === w ? 0 : 1));
      if (i > 1 && j > 1 && t === word.charCodeAt(j - 2) && term.charCodeAt(i - 2) === w) {
        d = Math.min(d, older[j - 2] + 1);
      }
      row[j] = d;
      if (d < rowMin) rowMin = d;
    }
    if (rowMin > limit && previousMin > limit) return Infinity;
    [older, previous, row] = [previous, row, older];
    previousMin = rowMin;
  }
  let best = Infinity;
  for (let step = 0; step <= limit; step++) {
    for (const j of step === 0 ? [m] : [m - step, m + step]) {
      if (j >= 1 && j <= n && previous[j] < best) {
        best = previous[j];
        nearestLength = j;
      }
    }
  }
  return best > limit ? Infinity : best;
}

/** `{ distance, length }`: the distance from `term` to the nearest prefix of `word` (see above) and that prefix's length. */
export function nearestPrefix(term, word, limit) {
  const distance = nearestPrefixDistance(term, word, limit);
  return { distance, length: distance === Infinity ? 0 : nearestLength };
}

/** The fuzzy match of an entry: `{ distance, tier, spans }` or null when a term finds no word within the allowance. */
function fuzzyEntry(norm, terms, termMasks, allowance) {
  const words = norm.words;
  if (words.length === 0) return null;
  const lead = leadWord(words, terms);
  let total = 0;
  let tier = 1;
  const spans = [];
  for (let t = 0; t < terms.length; t++) {
    let bestDistance = Infinity;
    let bestLength = 0;
    let bestWord = -1;
    // For the first term the lead word is tried first, so a tie goes to it and the entry ranks as tier 0.
    for (let k = 0; k < words.length; k++) {
      const w = t === 0 ? (k === 0 ? lead : k <= lead ? k - 1 : k) : k;
      const limit = allowance - total;
      if (missingBits(termMasks[t], norm.masks[w]) > limit) continue;
      const distance = nearestPrefixDistance(terms[t], words[w].text, limit);
      if (distance < bestDistance) {
        bestDistance = distance;
        bestLength = nearestLength;
        bestWord = w;
      }
    }
    if (bestWord < 0) return null;
    total += bestDistance;
    if (t === 0 && bestWord === lead) tier = 0;
    spans.push({ word: bestWord, length: bestLength });
  }
  const unique = [...new Map(spans.map((s) => [s.word, s])).values()];
  return { distance: total, tier, spans: unique };
}

/** The group sizes the header dropdown shows before "See all". */
export const DEFAULT_CAPS = { titles: 5, collections: 2, actors: 3, directors: 3 };

const LABELS = {
  titles: 'Titles',
  collections: 'Collections',
  actors: 'Actors',
  directors: 'Directors',
};

const compare = (a, b) => (a < b ? -1 : a > b ? 1 : 0);
const instant = (iso) => (iso ? Date.parse(iso) : Number.NEGATIVE_INFINITY);

// A hit is `{ entry, tier, distance, spans, rank, r }`. `r` (the name's sort key and the group's own ranking
// fields) is worked out by `rank` only when two hits tie on distance and tier, which most never do.
const fill = (hit) => (hit.r ??= hit.rank(hit.entry));
const head = (a, b) => a.distance - b.distance || a.tier - b.tier;
const byId = (a, b) => a.entry.id - b.entry.id;

const comparators = {
  titles: (a, b) =>
    head(a, b) ||
    fill(b).inProgress - fill(a).inProgress ||
    compare(b.r.played, a.r.played) ||
    compare(a.r.key, b.r.key) ||
    byId(a, b),
  collections: (a, b) => head(a, b) || compare(fill(a).key, fill(b).key) || byId(a, b),
  people: (a, b) =>
    head(a, b) || fill(b).count - fill(a).count || compare(a.r.key, b.r.key) || byId(a, b),
};

/** The four result groups with the list each is drawn from and how a hit is ranked. */
const roleLists = new WeakMap();

/** The people who act and the people who direct, worked out once per prepared index. */
function roles(prepared) {
  let lists = roleLists.get(prepared);
  if (!lists) {
    lists = {
      actors: prepared.people.filter((p) => p.actorCount > 0),
      directors: prepared.people.filter((p) => p.directorCount > 0),
    };
    roleLists.set(prepared, lists);
  }
  return lists;
}

function sources(prepared) {
  const { actors, directors } = roles(prepared);
  return [
    [
      'titles',
      prepared.films,
      comparators.titles,
      (f) => ({
        key: sortKey(f.title),
        inProgress: f.leftMs != null ? 1 : 0,
        played: instant(f.playedAt),
      }),
    ],
    [
      'collections',
      prepared.collections,
      comparators.collections,
      (c) => ({ key: sortKey(c.name) }),
    ],
    [
      'actors',
      actors,
      comparators.people,
      (p) => ({
        key: p.name.toLowerCase(),
        count: p.actorCount,
      }),
    ],
    [
      'directors',
      directors,
      comparators.people,
      (p) => ({
        key: p.name.toLowerCase(),
        count: p.directorCount,
      }),
    ],
  ];
}

/** The first `cap` hits in `order`, without sorting all of them: most keystrokes match thousands of entries. */
function top(hits, cap, order) {
  const best = [];
  if (cap <= 0) return best;
  for (const hit of hits) {
    if (best.length === cap && order(hit, best[cap - 1]) >= 0) continue;
    let at = best.length;
    while (at > 0 && order(hit, best[at - 1]) < 0) at--;
    best.splice(at, 0, hit);
    if (best.length > cap) best.pop();
  }
  return best;
}

function collect(list, rank, find) {
  const hits = [];
  for (const entry of list) {
    const found = find(normOf(entry));
    if (found)
      hits.push({ entry, rank, r: null, tier: found.tier, distance: found.distance, found });
  }
  return hits;
}

function build(prepared, find, spansOf, caps) {
  const groups = [];
  let total = 0;
  for (const [key, list, order, rank] of sources(prepared)) {
    const hits = collect(list, rank, find);
    if (hits.length === 0) continue;
    const items = top(hits, caps[key], order).map((hit) => ({
      ...hit.entry,
      tier: hit.tier,
      ranges: rangesFor(normOf(hit.entry), spansOf(hit)),
    }));
    groups.push({ key, label: LABELS[key], items, total: hits.length });
    total += hits.length;
  }
  return { groups, total };
}

/**
 * Searches the prepared index (`prepareIndex`) for `query`. Returns `{ query, groups, total, fuzzy }`: groups in the
 * order Titles, Collections, Actors, Directors, empty ones left out, each `{ key, label, items, total }` with items
 * capped (`caps` overrides `DEFAULT_CAPS`) and `total` counted before the cap. Each item is the entry plus `tier`
 * and `ranges`. When nothing matches exactly and the query is at least four characters, a typo-tolerant pass runs
 * and `fuzzy` is true.
 */
export function search(prepared, query, { caps = {} } = {}) {
  const text = String(query ?? '');
  const empty = { query: text, groups: [], total: 0, fuzzy: false };
  if (!prepared) return empty;
  const terms = queryTerms(text);
  if (terms.length === 0) return empty;
  const limits = { ...DEFAULT_CAPS, ...caps };

  const exact = build(
    prepared,
    (norm) => {
      const found = assignEntry(norm, terms);
      return found && { tier: found.tier, distance: 0, words: found.words };
    },
    (hit) => hit.found.words.map((word, i) => ({ word, length: terms[i].length })),
    limits,
  );
  if (exact.total > 0) return { query: text, ...exact, fuzzy: false };

  const length = terms.join('').length;
  if (length < 4) return empty;
  const allowance = length >= 7 ? 2 : 1;
  const termMasks = terms.map(letterMask);
  const fuzzy = build(
    prepared,
    (norm) => fuzzyEntry(norm, terms, termMasks, allowance),
    (hit) => hit.found.spans,
    limits,
  );
  return { query: text, ...fuzzy, fuzzy: fuzzy.total > 0 };
}
