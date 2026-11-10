// Display rules for a film's IMDb and Rotten Tomatoes ratings. No JSX: the Apple TV and native clients reuse them.

const OPENS = '(opens in a new tab)';

const isScore = (value) => value !== null && value !== undefined && Number.isFinite(Number(value));

/** "8.3": the IMDb score with one decimal, so a round 8 reads "8.0". */
export function formatImdbScore(score) {
  return Number(score).toFixed(1);
}

/** "84%": the Rotten Tomatoes percentage. */
export function formatRtScore(score) {
  return `${Math.round(Number(score))}%`;
}

/**
 * The items of the ratings row, IMDb first: `{ key, label, score, suffix, href, accessibleName }`.
 * A source without a score is left out, so the result is empty (and the row hidden) when there are none.
 * `href` is null for an item that is not a link (Rotten Tomatoes without a page); `suffix` is null when the
 * score stands alone.
 */
export function ratingItems(ratings) {
  const items = [];
  const imdb = ratings?.imdb;
  if (imdb && isScore(imdb.score)) {
    const score = formatImdbScore(imdb.score);
    items.push({
      key: 'imdb',
      label: 'IMDb',
      score,
      suffix: '/10',
      href: imdb.url || null,
      accessibleName: `IMDb rating ${score} out of 10 ${OPENS}`,
    });
  }
  const rt = ratings?.rottenTomatoes;
  if (rt && isScore(rt.score)) {
    const score = formatRtScore(rt.score);
    items.push({
      key: 'rt',
      label: 'Rotten Tomatoes',
      score,
      suffix: null,
      href: rt.url || null,
      accessibleName: `Rotten Tomatoes score ${score} ${OPENS}`,
    });
  }
  return items;
}

/** "Ratings last refreshed 08 Nov 12:41", or "Ratings never refreshed". `format` formats the ISO time. */
export function describeRatingsRefresh(iso, format) {
  return iso ? `Ratings last refreshed ${format(iso)}` : 'Ratings never refreshed';
}
