// The only place TMDB image URLs are built. A later local image cache changes just this.
const BASE = 'https://image.tmdb.org/t/p';

export const POSTER_SIZE = 'w342';
export const POSTER_SIZE_LARGE = 'w500';
export const BACKDROP_SIZE = 'w1280';
export const BACKDROP_SIZE_PHONE = 'w780';
export const PROFILE_SIZE = 'w185';

export function imageUrl(path, size = POSTER_SIZE) {
  if (!path) return null;
  return `${BASE}/${size}${path.startsWith('/') ? path : `/${path}`}`;
}
