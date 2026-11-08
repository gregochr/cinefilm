import { describe, expect, it } from 'vitest';
import { SUBTITLE_OFF, resolveAudioChoice, resolveSubtitleChoice } from './tracks.js';

// Two editions are two files: same tracks, different stream ids.
const editionA = {
  id: 1,
  audioStreams: [
    { id: 1, language: 'English', channels: '5.1', title: null },
    { id: 2, language: 'English', channels: '2.0', title: 'Commentary' },
    { id: 3, language: 'French', channels: '2.0', title: null },
  ],
  subtitleStreams: [
    { id: 10, language: 'English', forced: false },
    { id: 11, language: 'English', forced: true },
    { id: 12, language: 'French', forced: false },
  ],
};
const editionB = {
  id: 2,
  audioStreams: [
    { id: 21, language: 'english', channels: '7.1', title: null },
    { id: 22, language: 'English', channels: '2.0', title: 'Commentary' },
    { id: 23, language: 'French', channels: '5.1', title: null },
  ],
  subtitleStreams: [
    { id: 30, language: 'English', forced: false },
    { id: 31, language: 'english', forced: true },
  ],
};
const editions = [editionA, editionB];

describe('resolveSubtitleChoice', () => {
  it('keeps Off, and treats nothing saved as Off', () => {
    expect(resolveSubtitleChoice(editions, editionB, SUBTITLE_OFF)).toBe(SUBTITLE_OFF);
    expect(resolveSubtitleChoice(editions, editionB, undefined)).toBe(SUBTITLE_OFF);
    expect(resolveSubtitleChoice(editions, editionB, null)).toBe(SUBTITLE_OFF);
  });

  it("keeps a saved stream that belongs to the selected edition, whether it is a number or a picker's string", () => {
    expect(resolveSubtitleChoice(editions, editionA, 10)).toBe(10);
    expect(resolveSubtitleChoice(editions, editionB, '30')).toBe(30);
  });

  it("maps another edition's stream to the one with the same language and forced flag", () => {
    expect(resolveSubtitleChoice(editions, editionB, 10)).toBe(30);
    expect(resolveSubtitleChoice(editions, editionB, 11)).toBe(31);
    expect(resolveSubtitleChoice(editions, editionA, 31)).toBe(11);
  });

  it('does not turn a forced track into a full one or the other way round', () => {
    const onlyFull = {
      id: 3,
      audioStreams: [],
      subtitleStreams: [{ id: 40, language: 'English', forced: false }],
    };
    expect(resolveSubtitleChoice([...editions, onlyFull], onlyFull, 11)).toBe(SUBTITLE_OFF);
  });

  it('falls back to Off when the edition has no equivalent, or the saved id is unknown', () => {
    expect(resolveSubtitleChoice(editions, editionB, 12)).toBe(SUBTITLE_OFF);
    expect(resolveSubtitleChoice(editions, editionB, 999)).toBe(SUBTITLE_OFF);
  });

  it('treats a missing forced flag as not forced', () => {
    const plain = { id: 4, audioStreams: [], subtitleStreams: [{ id: 50, language: 'English' }] };
    expect(resolveSubtitleChoice([editionA, plain], plain, 10)).toBe(50);
  });
});

describe('resolveAudioChoice', () => {
  it('keeps a saved stream that belongs to the selected edition', () => {
    expect(resolveAudioChoice(editions, editionA, 2)).toBe(2);
    expect(resolveAudioChoice(editions, editionB, '22')).toBe(22);
  });

  it('maps by language and title first (Commentary stays Commentary)', () => {
    expect(resolveAudioChoice(editions, editionB, 2)).toBe(22);
    expect(resolveAudioChoice(editions, editionB, 1)).toBe(21);
    expect(resolveAudioChoice(editions, editionA, 22)).toBe(2);
  });

  it('then by language and channels when no title matches', () => {
    const untitled = {
      id: 5,
      subtitleStreams: [],
      audioStreams: [
        { id: 60, language: 'English', channels: '2.0', title: 'Director talk' },
        { id: 61, language: 'English', channels: '5.1', title: 'Main' },
      ],
    };
    expect(resolveAudioChoice([editionA, untitled], untitled, 1)).toBe(61);
    expect(resolveAudioChoice([editionA, untitled], untitled, 2)).toBe(60);
  });

  it('does not match across languages', () => {
    expect(resolveAudioChoice(editions, editionB, 3)).toBe(23);
    const english = {
      id: 6,
      subtitleStreams: [],
      audioStreams: [{ id: 70, language: 'English', channels: '2.0' }],
    };
    expect(resolveAudioChoice([editionA, english], english, 3)).toBeNull();
  });

  it('is null when nothing was saved, the id is unknown, or nothing matches', () => {
    expect(resolveAudioChoice(editions, editionB, undefined)).toBeNull();
    expect(resolveAudioChoice(editions, editionB, 999)).toBeNull();
    const none = { id: 7, subtitleStreams: [], audioStreams: [] };
    expect(resolveAudioChoice([editionA, none], none, 1)).toBeNull();
  });

  it('compares language and title without regard to case, and a missing title equals a missing title', () => {
    const shouting = {
      id: 8,
      subtitleStreams: [],
      audioStreams: [{ id: 80, language: 'ENGLISH', channels: '9.9', title: null }],
    };
    expect(resolveAudioChoice([editionA, shouting], shouting, 1)).toBe(80);
  });
});
