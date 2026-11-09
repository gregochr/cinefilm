// Collection display rules shared by the Collections tab, the grouped Films grid and the detail screens.

/** TMDB names its collections "Harry Potter Collection"; we show "Harry Potter". */
export function displayName(name = '') {
  return name.trim().replace(/\s+collection$/i, '');
}

/** Lower-case, leading "the " dropped: how titles and collection names sort (as the server's SortTitles). */
export function sortKey(text = '') {
  return text
    .trim()
    .toLowerCase()
    .replace(/^the\s+/, '');
}

const byName = (a, b) => {
  const x = sortKey(displayName(a.name));
  const y = sortKey(displayName(b.name));
  return x < y ? -1 : x > y ? 1 : 0;
};

export const isComplete = (c) => c.ownedCount >= c.totalCount;

/** Search on display name, the Show filter (`all` | `complete` | `missing`) and the sort (`name` | `added` | `size`). */
export function browseCollections(collections, { q = '', show = 'all', sort = 'name' } = {}) {
  const needle = q.trim().toLowerCase();
  const list = collections.filter((c) => {
    if (needle && !displayName(c.name).toLowerCase().includes(needle)) return false;
    if (show === 'complete') return isComplete(c);
    if (show === 'missing') return !isComplete(c);
    return true;
  });
  const order = {
    added: (a, b) => (b.lastAddedAt || '').localeCompare(a.lastAddedAt || ''),
    size: (a, b) => b.ownedCount - a.ownedCount,
    name: () => 0,
  }[sort];
  return list.sort((a, b) => (order ? order(a, b) : 0) || byName(a, b));
}

const FAN_SIZE = 3;
const BRIGHTNESS = [1, 0.8, 0.6];

/**
 * Which posters a fan shows and how. `posters` are paths (or null: a film with no artwork) ordered
 * earliest release first. Returns up to three slots, front first: `depth` 0 is the front poster,
 * `brightness` dims the ones behind (1 means no filter) and `shadow` is true for every poster that
 * has another behind it, so it casts a shadow onto it.
 */
export function fanPosters(posters = []) {
  const shown = posters.slice(0, FAN_SIZE);
  const last = shown.length - 1;
  return shown.map((path, depth) => ({
    path,
    depth,
    brightness: depth === last && last > 0 ? BRIGHTNESS[2] : BRIGHTNESS[Math.min(depth, 1)],
    shadow: depth < last,
  }));
}
