import { describe, expect, it } from 'vitest';
import { timeAgo } from './relativeTime.js';

const NOW = Date.parse('2026-11-14T12:00:00Z');
const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;
const ago = (ms) => timeAgo(new Date(NOW - ms).toISOString(), NOW);

describe('timeAgo', () => {
  it.each([
    [0, 'just now'],
    [MIN - 1, 'just now'],
    [MIN, '1 min ago'],
    [MIN + 1, '1 min ago'],
    [2 * MIN - 1, '1 min ago'],
    [2 * MIN, '2 min ago'],
    [12 * MIN, '12 min ago'],
    [HOUR - 1, '59 min ago'],
    [HOUR, '1 hour ago'],
    [2 * HOUR - 1, '1 hour ago'],
    [2 * HOUR, '2 hours ago'],
    [DAY - 1, '23 hours ago'],
    [DAY, '1 day ago'],
    [2 * DAY - 1, '1 day ago'],
    [2 * DAY, '2 days ago'],
    [40 * DAY, '40 days ago'],
  ])('%d ms before now reads "%s"', (ms, text) => {
    expect(ago(ms)).toBe(text);
  });

  it('treats a time in the future (clock skew) as just now', () => {
    expect(ago(-5 * MIN)).toBe('just now');
  });
});
