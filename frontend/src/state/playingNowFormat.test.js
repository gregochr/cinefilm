import { describe, expect, it } from 'vitest';
import { liveSessionFor, playingNowRow } from './playingNowFormat.js';

const MAC = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)';
const SAFARI_MAC =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15';

const entry = (over = {}) => ({
  playbackId: 'p1',
  editionId: 11,
  filmTitle: 'Heat',
  device: MAC,
  touch: false,
  environment: 'away',
  quality: '720p',
  ...over,
});
const session = (over = {}) => ({
  id: 's1',
  filmTitle: 'Heat',
  editionId: 11,
  rung: '720p',
  speed: 3.4,
  segmentsAhead: 12,
  startedAt: '2026-11-09T19:02:11Z',
  ...over,
});

describe('liveSessionFor', () => {
  it('is null with no sessions, none loaded yet or none for the edition', () => {
    expect(liveSessionFor(11, [])).toBeNull();
    expect(liveSessionFor(11, null)).toBeNull();
    expect(liveSessionFor(11, undefined)).toBeNull();
    expect(liveSessionFor(11, [session({ editionId: 12 })])).toBeNull();
  });

  it('matches by edition, not by title', () => {
    const other = session({ id: 'other', editionId: 12 });
    expect(liveSessionFor(11, [other, session()]).id).toBe('s1');
    expect(liveSessionFor(11, [session({ filmTitle: 'Another' }), other]).id).toBe('s1');
  });

  it('takes the newest started session if there were ever two (the server keeps one; this is a defence)', () => {
    const older = session({ id: 'older', startedAt: '2026-11-09T19:00:00Z' });
    const newer = session({ id: 'newer', startedAt: '2026-11-09T19:05:00Z' });
    expect(liveSessionFor(11, [older, newer]).id).toBe('newer');
    expect(liveSessionFor(11, [newer, older]).id).toBe('newer');
  });
});

describe('playingNowRow', () => {
  it('reads "{device} · {environment} · {quality}, converting on the fly" with a live session', () => {
    const row = playingNowRow(entry(), [session()]);
    expect(row).toEqual({
      playbackId: 'p1',
      filmTitle: 'Heat',
      device: 'Mac',
      detail: 'Mac · Away · 720p, converting on the fly',
      hint: 'Ends playback on Mac; it can resume from here.',
      sessionId: 's1',
    });
  });

  it('leaves "converting on the fly" out and has no session to stop for direct play', () => {
    const row = playingNowRow(entry({ quality: 'original' }), []);
    expect(row.detail).toBe('Mac · Away · Original');
    expect(row.sessionId).toBeNull();
  });

  it('names an iPad that sends a desktop Mac user agent by the touch hint', () => {
    const row = playingNowRow(entry({ device: SAFARI_MAC, touch: true }), [session()]);
    expect(row.device).toBe('Safari on iPad');
    expect(row.detail).toBe('Safari on iPad · Away · 720p, converting on the fly');
    expect(playingNowRow(entry({ device: SAFARI_MAC, touch: false }), []).device).toBe(
      'Safari on Mac',
    );
  });

  it('leaves out an environment or quality the report did not carry', () => {
    expect(playingNowRow(entry({ environment: null, quality: null }), [session()]).detail).toBe(
      'Mac',
    );
    expect(playingNowRow(entry({ environment: null }), []).detail).toBe('Mac · 720p');
  });

  it('says "Unknown device" for an unrecognised or missing user agent, with "that device" in the hint', () => {
    for (const device of [null, 'curl/8.4.0']) {
      const row = playingNowRow(entry({ device, touch: null }), [session()]);
      expect(row.device).toBe('Unknown device');
      expect(row.detail).toBe('Unknown device · Away · 720p, converting on the fly');
      expect(row.hint).toBe('Ends playback on that device; it can resume from here.');
    }
  });
});
