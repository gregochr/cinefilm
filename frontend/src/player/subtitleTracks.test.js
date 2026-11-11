import { describe, expect, it } from 'vitest';
import { FORCED_KEY, SUBTITLE_OFF, activeSubtitleKey, hasSubtitleTracks } from './tracks.js';

const english = { id: 10, index: 3, language: 'English', forced: false };
const englishForced = { id: 11, index: 4, language: 'English', forced: true };

describe('activeSubtitleKey (decision 6: forced subtitles)', () => {
  it('shows the forced English track by itself when Subtitles is Off', () => {
    expect(activeSubtitleKey([english, englishForced], SUBTITLE_OFF)).toBe(11);
  });

  it('shows nothing when Subtitles is Off and the film has no forced track', () => {
    expect(activeSubtitleKey([english], SUBTITLE_OFF)).toBeNull();
    expect(activeSubtitleKey([], SUBTITLE_OFF)).toBeNull();
    expect(activeSubtitleKey(undefined)).toBeNull();
  });

  it('shows the chosen full English track instead of the forced one', () => {
    expect(activeSubtitleKey([english, englishForced], 10)).toBe(10);
  });

  it('matches the choice whether it is a number or the string a picker gives back', () => {
    expect(activeSubtitleKey([english, englishForced], '10')).toBe(10);
  });

  it('lets the viewer choose the forced track explicitly', () => {
    expect(activeSubtitleKey([english, englishForced], 11)).toBe(11);
  });

  it("shows nothing for a choice that is not one of the film's streams", () => {
    expect(activeSubtitleKey([english, englishForced], 99)).toBeNull();
  });

  it('counts a forced track with no language as English', () => {
    expect(activeSubtitleKey([{ id: 12, index: 5, language: null, forced: true }])).toBe(12);
  });

  it('does not turn on a forced track in another language by itself', () => {
    expect(activeSubtitleKey([{ id: 13, index: 6, language: 'French', forced: true }])).toBeNull();
  });

  it('is not fooled by a language name that merely contains English', () => {
    expect(
      activeSubtitleKey([{ id: 14, index: 7, language: 'Middle English', forced: true }]),
    ).toBeNull();
  });

  it('identifies streams by index when the API sent no id', () => {
    expect(activeSubtitleKey([{ index: 4, language: 'English', forced: true }])).toBe(4);
  });
});

describe('the forced-subtitle track of an edition (a sidecar .srt)', () => {
  const forcedSubtitle = { source: 'SIDECAR', ownerVerified: false, version: 1700000000000 };

  it('shows by itself when Subtitles is Off, under its own key', () => {
    expect(activeSubtitleKey([], SUBTITLE_OFF, forcedSubtitle)).toBe(FORCED_KEY);
    expect(FORCED_KEY).toBe('forced');
  });

  it('beats the embedded forced track, because the API already chose the winner', () => {
    expect(activeSubtitleKey([english, englishForced], SUBTITLE_OFF, forcedSubtitle)).toBe(
      FORCED_KEY,
    );
  });

  it('is hidden when a full track is chosen', () => {
    expect(activeSubtitleKey([english, englishForced], 10, forcedSubtitle)).toBe(10);
    expect(activeSubtitleKey([english, englishForced], 11, forcedSubtitle)).toBe(11);
  });

  it('is hidden for a choice that is not one of the streams, as no stream shows then either', () => {
    expect(activeSubtitleKey([english], 99, forcedSubtitle)).toBeNull();
  });

  it('is not there when the edition has none: the embedded forced track still shows', () => {
    expect(activeSubtitleKey([english, englishForced], SUBTITLE_OFF, null)).toBe(11);
    expect(activeSubtitleKey([english], SUBTITLE_OFF, undefined)).toBeNull();
  });
});

describe('hasSubtitleTracks', () => {
  it('is true with streams, or with only a forced subtitle, and false with neither', () => {
    expect(hasSubtitleTracks({ subtitleStreams: [english], forcedSubtitle: null })).toBe(true);
    expect(
      hasSubtitleTracks({
        subtitleStreams: [],
        forcedSubtitle: { source: 'SIDECAR', ownerVerified: true, version: 1 },
      }),
    ).toBe(true);
    expect(hasSubtitleTracks({ subtitleStreams: [], forcedSubtitle: null })).toBe(false);
    expect(hasSubtitleTracks({ subtitleStreams: [] })).toBe(false);
  });
});
