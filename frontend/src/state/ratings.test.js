import { describe, expect, it } from 'vitest';
import {
  describeRatingsRefresh,
  formatImdbScore,
  formatRtScore,
  ratingItems,
} from './ratings.js';

describe('score formatting', () => {
  it('shows the IMDb score with one decimal', () => {
    expect(formatImdbScore(8.3)).toBe('8.3');
    expect(formatImdbScore(8)).toBe('8.0');
    expect(formatImdbScore('7.25')).toBe('7.3');
  });

  it('shows the Rotten Tomatoes score as a whole percentage', () => {
    expect(formatRtScore(84)).toBe('84%');
    expect(formatRtScore(0)).toBe('0%');
    expect(formatRtScore(99.6)).toBe('100%');
  });
});

describe('ratingItems', () => {
  const both = {
    imdb: { score: 8.3, votes: 1234567, url: 'https://www.imdb.com/title/tt0113277/' },
    rottenTomatoes: { score: 84, url: 'https://www.rottentomatoes.com/m/heat_1995' },
  };

  it('lists IMDb then Rotten Tomatoes, each with its label, score, suffix, link and accessible name', () => {
    expect(ratingItems(both)).toEqual([
      {
        key: 'imdb',
        label: 'IMDb',
        score: '8.3',
        suffix: '/10',
        href: 'https://www.imdb.com/title/tt0113277/',
        accessibleName: 'IMDb rating 8.3 out of 10 (opens in a new tab)',
      },
      {
        key: 'rt',
        label: 'Rotten Tomatoes',
        score: '84%',
        suffix: null,
        href: 'https://www.rottentomatoes.com/m/heat_1995',
        accessibleName: 'Rotten Tomatoes score 84% (opens in a new tab)',
      },
    ]);
  });

  it('leaves out a source with no score', () => {
    expect(ratingItems({ ...both, rottenTomatoes: null }).map((i) => i.key)).toEqual(['imdb']);
    expect(ratingItems({ ...both, imdb: null }).map((i) => i.key)).toEqual(['rt']);
  });

  it('is empty when there are no ratings at all, or none were sent', () => {
    expect(ratingItems({ imdb: null, rottenTomatoes: null })).toEqual([]);
    expect(ratingItems(null)).toEqual([]);
    expect(ratingItems(undefined)).toEqual([]);
  });

  it('treats a missing or unreadable score as no score, but keeps a score of zero', () => {
    expect(ratingItems({ imdb: { score: null }, rottenTomatoes: { score: 'abc' } })).toEqual([]);
    expect(ratingItems({ imdb: { score: undefined }, rottenTomatoes: null })).toEqual([]);
    expect(ratingItems({ imdb: null, rottenTomatoes: { score: 0, url: null } })[0].score).toBe(
      '0%',
    );
  });

  it('gives an item without a page no link', () => {
    const items = ratingItems({
      imdb: { score: 7.1, votes: null, url: null },
      rottenTomatoes: { score: 60, url: '' },
    });
    expect(items.map((i) => i.href)).toEqual([null, null]);
  });
});

describe('describeRatingsRefresh', () => {
  it('says when ratings were last refreshed, using the given formatter', () => {
    expect(describeRatingsRefresh('2026-11-08T12:41:10Z', (iso) => `at ${iso}`)).toBe(
      'Ratings last refreshed at 2026-11-08T12:41:10Z',
    );
  });

  it('says never when they were not', () => {
    expect(describeRatingsRefresh(null, () => 'unused')).toBe('Ratings never refreshed');
  });
});
