// The only place image URLs are built. TMDB paths (/abc.jpg) become TMDB URLs. Artwork imported from Plex is stored by
// the server and its path (/api/art/<hash>.jpg) is a same-origin URL; the server resizes it on first request to one of
// four widths (342, 500, 780, 1280), so a stored path gets ?w= for the size asked for. A size with no matching width
// is served whole.
const BASE = 'https://image.tmdb.org/t/p';
const LOCAL_ART = '/api/art/';

export const POSTER_SIZE_THUMB = 'w92';
export const POSTER_SIZE = 'w342';
export const POSTER_SIZE_LARGE = 'w500';
export const STILL_SIZE = 'w780';
export const BACKDROP_SIZE = 'w1280';
export const PROFILE_SIZE = 'w185';

// The widths the server offers, by TMDB size token; the two small tokens use the smallest copy.
const LOCAL_WIDTHS = new Map([
  ['w92', 342],
  ['w185', 342],
  ['w342', 342],
  ['w500', 500],
  ['w780', 780],
  ['w1280', 1280],
]);

export function imageUrl(path, size = POSTER_SIZE) {
  if (!path) return null;
  if (path.startsWith(LOCAL_ART)) {
    const width = LOCAL_WIDTHS.get(size);
    return width ? `${path}?w=${width}` : path;
  }
  return `${BASE}/${size}${path.startsWith('/') ? path : `/${path}`}`;
}
