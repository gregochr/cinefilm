import { describe, expect, it } from 'vitest';
import { encoderLabel, encoderStatus, formatBytes, seekIndexText } from './transcodingFormat.js';

describe('encoderLabel', () => {
  it('names the three encoders and shows an unknown one as is', () => {
    expect(encoderLabel('vaapi')).toBe('VAAPI (hardware)');
    expect(encoderLabel('software')).toBe('Software (x264)');
    expect(encoderLabel('unavailable')).toBe('Unavailable');
    expect(encoderLabel('nvenc')).toBe('nvenc');
  });
});

describe('encoderStatus', () => {
  it('adds "ready" to a found encoder and says only "Unavailable" when there is none', () => {
    expect(encoderStatus('vaapi')).toBe('VAAPI (hardware) · ready');
    expect(encoderStatus('software')).toBe('Software (x264) · ready');
    expect(encoderStatus('unavailable')).toBe('Unavailable');
  });
});

describe('seekIndexText', () => {
  it('reads "n of N films" with thousands separators', () => {
    expect(seekIndexText({ indexed: 0, total: 538 })).toBe('0 of 538 films');
    expect(seekIndexText({ indexed: 1409, total: 1412 })).toBe('1,409 of 1,412 films');
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
