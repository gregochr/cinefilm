// The only place image URLs are built. TMDB paths (/abc.jpg) become TMDB URLs; artwork imported from Plex is stored
// by the server and its path (/api/art/<hash>.jpg) is already a same-origin URL, so it passes through unchanged.
const BASE = 'https://image.tmdb.org/t/p';
const LOCAL_ART = '/api/art/';

export const POSTER_SIZE = 'w342';
export const POSTER_SIZE_LARGE = 'w500';
export const BACKDROP_SIZE = 'w1280';
export const BACKDROP_SIZE_PHONE = 'w780';
export const PROFILE_SIZE = 'w185';

export function imageUrl(path, size = POSTER_SIZE) {
  if (!path) return null;
  if (path.startsWith(LOCAL_ART)) return path;
  return `${BASE}/${size}${path.startsWith('/') ? path : `/${path}`}`;
}
