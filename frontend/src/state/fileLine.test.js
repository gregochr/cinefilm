import { describe, expect, it } from 'vitest';
import { fileLine } from './fileLine.js';

const ADDED = '2024-03-05T10:00:00Z';

describe('the file line of a film in one piece', () => {
  it('reads Original file, resolution and codec, the main audio and when it was added', () => {
    const edition = {
      name: 'Theatrical Cut',
      runtimeMs: 10_200_000,
      file: { width: 1920, videoCodec: 'H.264', audioSummary: '5.1', addedAt: ADDED },
    };
    expect(fileLine(edition)).toBe('Original file · 1080p H.264 · 5.1 · added March 2024');
  });

  it('is the same for an edition that says it has one part', () => {
    const file = { width: 1280, videoCodec: 'HEVC', audioSummary: 'Stereo' };
    expect(fileLine({ name: 'Cut', parts: 1, file })).toBe(fileLine({ name: 'Cut', file }));
    expect(fileLine({ name: 'Cut', parts: 1, file })).toBe('Original file · 720p HEVC · Stereo');
  });

  it('leaves out what is unknown and copes with no file at all', () => {
    expect(fileLine({ name: 'Cut', file: { height: 536, videoCodec: 'H.264' } })).toBe(
      'Original file · H.264',
    );
    expect(fileLine({ name: 'Cut' })).toBe('Original file');
    expect(fileLine({ name: 'Cut', file: { addedAt: 'not a date' } })).toBe('Original file');
  });
});

describe('the file line of an edition split across several files', () => {
  const extended = {
    name: 'Extended Edition',
    parts: 2,
    runtimeMs: 14_100_000,
    file: { width: 1920, videoCodec: 'HEVC', audioSummary: '5.1', addedAt: ADDED },
  };

  it('reads the edition name, how many parts, the whole runtime and the picture', () => {
    expect(fileLine(extended)).toBe('Extended Edition · 2 parts · 3h 55m · 1080p HEVC');
  });

  it('counts three parts and never numbers or names one', () => {
    const line = fileLine({ ...extended, parts: 3 });
    expect(line).toBe('Extended Edition · 3 parts · 3h 55m · 1080p HEVC');
    expect(line).not.toMatch(/pt\s*\d|part\s*\d|Original file|added/i);
  });

  it('leaves out an unknown picture', () => {
    expect(fileLine({ ...extended, file: null })).toBe('Extended Edition · 2 parts · 3h 55m');
    expect(fileLine({ ...extended, file: { height: 800 } })).toBe(
      'Extended Edition · 2 parts · 3h 55m',
    );
  });
});
