import { describe, expect, it } from 'vitest';
import {
  FORCED_KEY,
  FULL_KEY,
  SUBTITLE_OFF,
  TEXT_SUBTITLE_FORMATS,
  activeSubtitleKey,
  effectiveSubtitleChoice,
  hasSubtitleTracks,
  isEnglishLabel,
  nextSubtitle,
  resolveSubtitleChoice,
  subtitleOptions,
} from './tracks.js';

const FULL = { source: 'SIDECAR', version: 1700000000000 };
const english = { id: 10, index: 3, language: 'English', forced: false };
const englishForced = { id: 11, index: 4, language: 'English', forced: true };
const french = { id: 12, index: 5, language: 'French', forced: false };

describe('the full subtitle choice', () => {
  it('is the literal "full"', () => {
    expect(FULL_KEY).toBe('full');
  });

  it('is offered as "English (full)" right after Off, before the streams', () => {
    expect(subtitleOptions([english], FULL).map((o) => [o.value, o.label])).toEqual([
      ['off', 'Off'],
      ['full', 'English (full)'],
      [10, 'English'],
    ]);
  });

  it('is not offered when the edition has no full subtitle', () => {
    expect(subtitleOptions([english]).map((o) => o.value)).toEqual(['off', 10]);
    expect(subtitleOptions([english], null).map((o) => o.value)).toEqual(['off', 10]);
  });
});

describe('nextSubtitle (the C key) with a full subtitle', () => {
  it('cycles Off → English (full) → the streams → Off', () => {
    const streams = [english, englishForced];
    expect(nextSubtitle(streams, SUBTITLE_OFF, FULL)).toBe(FULL_KEY);
    expect(nextSubtitle(streams, FULL_KEY, FULL)).toBe(10);
    expect(nextSubtitle(streams, 10, FULL)).toBe(11);
    expect(nextSubtitle(streams, 11, FULL)).toBe(SUBTITLE_OFF);
  });

  it('cycles Off ↔ English (full) when it is the only choice', () => {
    expect(nextSubtitle([], SUBTITLE_OFF, FULL)).toBe(FULL_KEY);
    expect(nextSubtitle([], FULL_KEY, FULL)).toBe(SUBTITLE_OFF);
  });

  it('skips it for an edition without one', () => {
    expect(nextSubtitle([english], SUBTITLE_OFF, null)).toBe(10);
  });
});

describe('activeSubtitleKey with a full subtitle', () => {
  it('shows the full track when it is chosen, and hides the forced one', () => {
    expect(activeSubtitleKey([englishForced], FULL_KEY, { version: 1 }, FULL)).toBe(FULL_KEY);
  });

  it('shows the forced track again when Off is chosen', () => {
    expect(activeSubtitleKey([], SUBTITLE_OFF, { version: 1 }, FULL)).toBe(FORCED_KEY);
  });

  it('shows a chosen stream, not the full track', () => {
    expect(activeSubtitleKey([english], 10, null, FULL)).toBe(10);
  });

  it('treats a chosen "full" on an edition without one as Off', () => {
    expect(activeSubtitleKey([english], FULL_KEY, null, null)).toBeNull();
    expect(activeSubtitleKey([english], FULL_KEY, { version: 1 }, null)).toBe(FORCED_KEY);
  });

  it('treats a choice of null (never chosen) as Off', () => {
    expect(activeSubtitleKey([english, englishForced], null)).toBe(11);
  });
});

describe('resolveSubtitleChoice with "full" and "never chosen"', () => {
  const withFull = { id: 1, subtitleStreams: [english], fullSubtitle: FULL };
  const noFull = { id: 2, subtitleStreams: [english], fullSubtitle: null };
  const noEnglish = { id: 3, subtitleStreams: [french, englishForced], fullSubtitle: null };
  const editions = [withFull, noFull, noEnglish];

  it('keeps null for nothing saved, so "never chosen" stays visible', () => {
    expect(resolveSubtitleChoice(editions, withFull, null)).toBeNull();
    expect(resolveSubtitleChoice(editions, withFull, undefined)).toBeNull();
  });

  it('keeps a saved Off as Off', () => {
    expect(resolveSubtitleChoice(editions, withFull, SUBTITLE_OFF)).toBe(SUBTITLE_OFF);
  });

  it('maps a saved "full" to "full" when the edition has a full subtitle', () => {
    expect(resolveSubtitleChoice(editions, withFull, FULL_KEY)).toBe(FULL_KEY);
  });

  it('maps a saved "full" to the English text stream on an edition without one', () => {
    expect(resolveSubtitleChoice(editions, noFull, FULL_KEY)).toBe(10);
  });

  it('skips a bitmap-format English stream listed before a text one', () => {
    const bitmap = { id: 20, language: 'English', forced: false, format: 'dvd_subtitle' };
    const text = { id: 21, language: 'English', forced: false, format: 'subrip' };
    const edition = { id: 5, subtitleStreams: [bitmap, text], fullSubtitle: null };
    expect(resolveSubtitleChoice([edition], edition, FULL_KEY)).toBe(21);
  });

  it('falls back to Off when the only English stream is a bitmap', () => {
    const bitmap = { id: 20, language: 'English', forced: false, format: 'hdmv_pgs_subtitle' };
    const edition = { id: 5, subtitleStreams: [bitmap], fullSubtitle: null };
    expect(resolveSubtitleChoice([edition], edition, FULL_KEY)).toBe(SUBTITLE_OFF);
  });

  it('accepts an English stream with a missing format, and any listed text format', () => {
    const edition = {
      id: 5,
      subtitleStreams: [{ id: 22, language: 'English' }],
      fullSubtitle: null,
    };
    expect(resolveSubtitleChoice([edition], edition, FULL_KEY)).toBe(22);
    for (const format of TEXT_SUBTITLE_FORMATS) {
      const e = {
        id: 6,
        subtitleStreams: [{ id: 23, language: 'English', format }],
        fullSubtitle: null,
      };
      expect(resolveSubtitleChoice([e], e, FULL_KEY)).toBe(23);
    }
  });

  it('maps a saved "full" to Off when there is no full file and no English text stream', () => {
    expect(resolveSubtitleChoice(editions, noEnglish, FULL_KEY)).toBe(SUBTITLE_OFF);
    expect(
      resolveSubtitleChoice([], { id: 4, subtitleStreams: [{ language: null }] }, FULL_KEY),
    ).toBe(SUBTITLE_OFF);
  });
});

describe('hasSubtitleTracks', () => {
  it('counts the full subtitle on its own', () => {
    expect(
      hasSubtitleTracks({ subtitleStreams: [], forcedSubtitle: null, fullSubtitle: FULL }),
    ).toBe(true);
  });

  it('is false with no streams and no files', () => {
    expect(
      hasSubtitleTracks({ subtitleStreams: [], forcedSubtitle: null, fullSubtitle: null }),
    ).toBe(false);
    expect(hasSubtitleTracks({ subtitleStreams: [] })).toBe(false);
  });
});

describe('isEnglishLabel', () => {
  it.each(['english', 'English', 'ENG', 'eng', 'en', 'EN', '  English  ', ' en '])(
    'reads %j as English',
    (language) => {
      expect(isEnglishLabel(language)).toBe(true);
    },
  );

  it.each(['French', 'Middle English', 'enx', '', '   ', null, undefined])(
    'does not read %j as English',
    (language) => {
      expect(isEnglishLabel(language)).toBe(false);
    },
  );
});

describe('effectiveSubtitleChoice (decisions 62 and 68: on by default)', () => {
  const base = {
    choice: null,
    fullNeed: 'NEEDED',
    fullSubtitle: FULL,
    audioLanguage: 'French',
  };

  it('turns the full subtitle on when nothing was chosen, it is needed, there is a file and the audio is not English', () => {
    expect(effectiveSubtitleChoice(base)).toBe(FULL_KEY);
    expect(effectiveSubtitleChoice({ ...base, choice: undefined })).toBe(FULL_KEY);
  });

  it('counts unlabelled audio as not English', () => {
    expect(effectiveSubtitleChoice({ ...base, audioLanguage: null })).toBe(FULL_KEY);
    expect(effectiveSubtitleChoice({ ...base, audioLanguage: undefined })).toBe(FULL_KEY);
    expect(effectiveSubtitleChoice({ ...base, audioLanguage: ' ' })).toBe(FULL_KEY);
  });

  it('is Off when a choice was made, Off included', () => {
    expect(effectiveSubtitleChoice({ ...base, choice: SUBTITLE_OFF })).toBe(SUBTITLE_OFF);
  });

  it('keeps any choice that was made', () => {
    expect(effectiveSubtitleChoice({ ...base, choice: 10 })).toBe(10);
    expect(effectiveSubtitleChoice({ ...base, choice: FULL_KEY, fullNeed: 'NOT_NEEDED' })).toBe(
      FULL_KEY,
    );
  });

  it.each(['NOT_NEEDED', 'UNSURE', 'UNKNOWN', null, undefined])(
    'is Off when the film’s need is %j',
    (fullNeed) => {
      expect(effectiveSubtitleChoice({ ...base, fullNeed })).toBe(SUBTITLE_OFF);
    },
  );

  it('is Off when the edition has no full subtitle', () => {
    expect(effectiveSubtitleChoice({ ...base, fullSubtitle: null })).toBe(SUBTITLE_OFF);
    expect(effectiveSubtitleChoice({ ...base, fullSubtitle: undefined })).toBe(SUBTITLE_OFF);
  });

  it.each(['English', 'english', 'eng', 'EN', ' en '])(
    'is Off when the selected audio is labelled %j',
    (audioLanguage) => {
      expect(effectiveSubtitleChoice({ ...base, audioLanguage })).toBe(SUBTITLE_OFF);
    },
  );

  it('falls back to Off when the audio is switched to English while nothing is chosen', () => {
    expect(effectiveSubtitleChoice({ ...base, audioLanguage: 'French' })).toBe(FULL_KEY);
    expect(effectiveSubtitleChoice({ ...base, audioLanguage: 'English' })).toBe(SUBTITLE_OFF);
    expect(effectiveSubtitleChoice({ ...base, audioLanguage: 'French' })).toBe(FULL_KEY);
  });
});
