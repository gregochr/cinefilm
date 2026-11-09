import { describe, expect, it } from 'vitest';
import {
  isSlowRung,
  levelToast,
  resolveQuality,
  rungForHeight,
  rungForLevel,
  rungName,
  topRung,
  workerOffline,
} from './ladder.js';

const renditions = [
  { id: 'original', height: 820, bitrate: 15000000, copy: true },
  { id: '720p', height: 720, bitrate: 2500000, copy: false },
  { id: '480p', height: 480, bitrate: 1000000, copy: false },
  { id: '360p', height: 360, bitrate: 600000, copy: false },
];

const q = (id, available = true) => ({ id, bitrate: null, premade: false, available });
const online = [q('auto'), q('original'), q('1080p', false), q('720p'), q('480p'), q('360p')];
const offline = [q('auto', false), q('original'), q('720p', false), q('360p', false)];

describe('rungName', () => {
  it('names auto and original, and leaves a height as it is', () => {
    expect(rungName('auto')).toBe('Auto');
    expect(rungName('original')).toBe('Original');
    expect(rungName('360p')).toBe('360p');
  });
});

describe('isSlowRung', () => {
  it.each(['480p', '360p', '240p'])('%s is slow', (id) => expect(isSlowRung(id)).toBe(true));
  it.each(['original', '1080p', '720p', 'auto'])('%s is not slow', (id) =>
    expect(isSlowRung(id)).toBe(false),
  );
});

describe('resolveQuality', () => {
  it('keeps a remembered quality the edition lists as available, 360p included', () => {
    expect(resolveQuality('720p', online)).toBe('720p');
    expect(resolveQuality('360p', online)).toBe('360p');
    expect(resolveQuality('original', online)).toBe('original');
  });

  it('falls back to Auto for a quality the edition does not list', () => {
    expect(resolveQuality('240p', online)).toBe('auto');
  });

  it('falls back to Auto for a quality listed but unavailable', () => {
    expect(resolveQuality('1080p', online)).toBe('auto');
  });

  it('is Auto with nothing remembered', () => {
    expect(resolveQuality(undefined, online)).toBe('auto');
    expect(resolveQuality(null, online)).toBe('auto');
    expect(resolveQuality('auto', online)).toBe('auto');
  });

  it('is the original when the worker is offline, whatever was remembered', () => {
    expect(resolveQuality('auto', offline)).toBe('original');
    expect(resolveQuality('720p', offline)).toBe('original');
    expect(resolveQuality(undefined, offline)).toBe('original');
    expect(resolveQuality('original', offline)).toBe('original');
  });

  it('is Auto when the edition lists no qualities at all', () => {
    expect(resolveQuality('720p')).toBe('auto');
    expect(resolveQuality('720p', [])).toBe('auto');
  });
});

describe('workerOffline', () => {
  it('is when Auto is listed but unavailable', () => {
    expect(workerOffline(offline)).toBe(true);
  });
  it('is not when Auto is available', () => {
    expect(workerOffline(online)).toBe(false);
  });
  it('is not when Auto is not listed (nothing says the worker is down)', () => {
    expect(workerOffline([q('original')])).toBe(false);
    expect(workerOffline()).toBe(false);
  });
});

describe('rungForHeight and rungForLevel', () => {
  it('finds the rung by height', () => {
    expect(rungForHeight(renditions, 480)).toBe('480p');
    expect(rungForHeight(renditions, 820)).toBe('original');
    expect(rungForHeight(renditions, 0)).toBeNull();
    expect(rungForHeight(renditions, 481)).toBeNull();
  });

  it('maps a level by height first', () => {
    expect(rungForLevel(renditions, { height: 720, bitrate: 1 })).toBe('720p');
  });

  it('falls back to the exact bitrate when the level has no resolution', () => {
    expect(rungForLevel(renditions, { height: 0, bitrate: 1000000 })).toBe('480p');
    expect(rungForLevel(renditions, { bitrate: 600000 })).toBe('360p');
  });

  it('falls back to the bitrate when the height matches no rung', () => {
    expect(rungForLevel(renditions, { height: 999, bitrate: 2500000 })).toBe('720p');
  });

  it('is null when neither matches', () => {
    expect(rungForLevel(renditions, { height: 999, bitrate: 7 })).toBeNull();
  });

  it('heights are unique across a ladder, so a height names one rung', () => {
    const heights = renditions.map((r) => r.height);
    expect(new Set(heights).size).toBe(heights.length);
  });
});

describe('topRung', () => {
  it('is the tallest rung wherever it sits in the list', () => {
    expect(topRung(renditions)).toBe('original');
    expect(topRung([...renditions].reverse())).toBe('original');
    expect(topRung([renditions[2], renditions[1]])).toBe('720p');
  });

  it('is the original for no renditions (direct play)', () => {
    expect(topRung([])).toBe('original');
    expect(topRung(undefined)).toBe('original');
  });
});

describe('levelToast', () => {
  it('says "Switching to 720p for smoother playback" on a step down', () => {
    expect(levelToast(renditions, 'original', '720p')).toBe(
      'Switching to 720p for smoother playback',
    );
    expect(levelToast(renditions, '720p', '480p')).toBe('Switching to 480p for smoother playback');
  });

  it('says "Back to 720p" on a step up, and "Back to Original" at the top', () => {
    expect(levelToast(renditions, '480p', '720p')).toBe('Back to 720p');
    expect(levelToast(renditions, '720p', 'original')).toBe('Back to Original');
  });

  it('says nothing when the rung did not change or either is unknown', () => {
    expect(levelToast(renditions, '720p', '720p')).toBeNull();
    expect(levelToast(renditions, null, '720p')).toBeNull();
    expect(levelToast(renditions, '720p', null)).toBeNull();
    expect(levelToast(renditions, '1080p', '720p')).toBeNull();
    expect(levelToast(renditions, '720p', '1080p')).toBeNull();
  });
});
