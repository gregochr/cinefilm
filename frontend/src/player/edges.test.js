import { describe, expect, it } from 'vitest';
import { keyToAction } from './keymap.js';
import { spokenTime } from './time.js';
import { nextSubtitle, subtitleOptions } from './tracks.js';

describe('player edge cases', () => {
  it('maps C to caption cycling when no options are passed (subtitles assumed)', () => {
    expect(keyToAction({ key: 'c' })).toEqual({ type: 'cycleCaptions' });
    expect(keyToAction({ key: 'c' }, { hasSubtitles: false })).toBeNull();
  });

  it('speaks a whole number of hours with zero minutes', () => {
    expect(spokenTime(3605000)).toBe('1 hour 0 minutes 5 seconds');
    expect(spokenTime(61000)).toBe('1 minute 1 second');
    expect(spokenTime(5000)).toBe('5 seconds');
  });

  it('offers only Off when no streams are passed', () => {
    expect(subtitleOptions()).toHaveLength(1);
    expect(nextSubtitle()).toBe('off');
  });
});
