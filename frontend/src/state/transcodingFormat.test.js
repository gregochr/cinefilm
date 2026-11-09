import { describe, expect, it } from 'vitest';
import {
  aheadText,
  encoderLabel,
  formatBytes,
  formatSpeed,
  indexText,
} from './transcodingFormat.js';

describe('encoderLabel', () => {
  it('names the three encoders and shows an unknown one as is', () => {
    expect(encoderLabel('vaapi')).toBe('VAAPI (hardware)');
    expect(encoderLabel('software')).toBe('Software (x264)');
    expect(encoderLabel('unavailable')).toBe('Unavailable');
    expect(encoderLabel('nvenc')).toBe('nvenc');
  });
});

describe('indexText', () => {
  it('reads "n of N films indexed"', () => {
    expect(indexText({ indexed: 0, total: 538 })).toBe('0 of 538 films indexed');
  });
});

describe('formatBytes', () => {
  it.each([
    [0, '0 B'],
    [-5, '0 B'],
    [NaN, '0 B'],
    [undefined, '0 B'],
    [1, '1 B'],
    [1023, '1023 B'],
    [1024, '1.0 KB'],
    [1536, '1.5 KB'],
    [1048575, '1024.0 KB'],
    [1048576, '1.0 MB'],
    [1288490188, '1.2 GB'],
    [5 * 1024 ** 4, '5.0 TB'],
    [5000 * 1024 ** 4, '5000.0 TB'],
  ])('%s is %s', (bytes, text) => {
    expect(formatBytes(bytes)).toBe(text);
  });
});

describe('formatSpeed', () => {
  it('shows times real time with one decimal, and a dash when unknown', () => {
    expect(formatSpeed(3.44)).toBe('3.4×');
    expect(formatSpeed(0)).toBe('0.0×');
    expect(formatSpeed(null)).toBe('—');
    expect(formatSpeed(undefined)).toBe('—');
    expect(formatSpeed(NaN)).toBe('—');
  });
});

describe('aheadText', () => {
  it('is singular for one segment and plural otherwise, zero included', () => {
    expect(aheadText(1)).toBe('1 segment ahead');
    expect(aheadText(0)).toBe('0 segments ahead');
    expect(aheadText(12)).toBe('12 segments ahead');
  });
});
