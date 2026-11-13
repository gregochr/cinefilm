import { describe, expect, it } from 'vitest';
import { endThresholdMs, hasReachedEnd, resumePosition } from './resume.js';
import {
  formatClock,
  formatLeft,
  formatRemaining,
  formatResume,
  formatRuntime,
  fraction,
  spokenTime,
} from './time.js';
import {
  SUBTITLE_OFF,
  audioLabel,
  audioText,
  currentQualityLabel,
  formatBitrate,
  nextSubtitle,
  qualityDetail,
  qualityName,
  streamKey,
  subtitleLabel,
} from './tracks.js';

describe('time: degenerate inputs', () => {
  it.each([undefined, null, NaN, Infinity, -5000])('%s formats as zero', (ms) => {
    expect(formatClock(ms)).toBe('00:00:00');
  });

  it('spells singular and plural', () => {
    expect(spokenTime(3_661_000)).toBe('1 hour 1 minute 1 second');
    expect(spokenTime(7_320_000)).toBe('2 hours 2 minutes 0 seconds');
    expect(spokenTime(60_000)).toBe('1 minute 0 seconds');
    expect(spokenTime(0)).toBe('0 seconds');
  });

  it('formats the Resume label under and over an hour', () => {
    expect(formatResume(271_000)).toBe('4:31');
    expect(formatResume(3_600_000)).toBe('1:00:00');
    expect(formatResume(3_871_000)).toBe('1:04:31');
  });

  it('formats runtimes with and without hours', () => {
    expect(formatRuntime(48 * 60_000)).toBe('48m');
    expect(formatRuntime(170 * 60_000)).toBe('2h 50m');
    expect(formatRuntime(60 * 60_000)).toBe('1h 0m');
  });

  it('time left never goes negative when the position passes the end', () => {
    expect(formatLeft(11_000, 10_000)).toBe('0m left');
    expect(formatRemaining(11_000, 10_000)).toBe('−00:00:00');
  });

  it('time left treats missing values as zero', () => {
    expect(formatRemaining(undefined, 10_000)).toBe('−00:00:10');
    expect(formatRemaining(5_000, undefined)).toBe('−00:00:00');
  });

  it('progress fraction is clamped to 0..1 and zero without a duration', () => {
    expect(fraction(5, 10)).toBe(0.5);
    expect(fraction(-5, 10)).toBe(0);
    expect(fraction(15, 10)).toBe(1);
    expect(fraction(5, 0)).toBe(0);
    expect(fraction(5, undefined)).toBe(0);
  });
});

describe('resume: boundaries', () => {
  it('never rewinds below zero and treats no saved position as the start', () => {
    expect(resumePosition(9_999)).toBe(0);
    expect(resumePosition(10_000)).toBe(0);
    expect(resumePosition(10_001)).toBe(1);
    expect(resumePosition(undefined)).toBe(0);
    expect(resumePosition(null)).toBe(0);
  });

  it('uses the credits marker only when it is a positive number', () => {
    expect(endThresholdMs(10_000, 8_000)).toBe(8_000);
    expect(endThresholdMs(10_000, 0)).toBe(9_700);
    expect(endThresholdMs(10_000, null)).toBe(9_700);
    expect(endThresholdMs(10_000, undefined)).toBe(9_700);
    expect(endThresholdMs(10_000, NaN)).toBe(9_700);
  });

  it('does not reach the end between 92% and 96% of the runtime without a marker', () => {
    expect(hasReachedEnd(9_200, 10_000, null)).toBe(false);
    expect(hasReachedEnd(9_600, 10_000, null)).toBe(false);
  });

  it('reaches the end exactly at the threshold, not a millisecond before', () => {
    expect(hasReachedEnd(9_699, 10_000)).toBe(false);
    expect(hasReachedEnd(9_700, 10_000)).toBe(true);
    expect(hasReachedEnd(9_701, 10_000)).toBe(true);
    expect(hasReachedEnd(7_999, 10_000, 8_000)).toBe(false);
    expect(hasReachedEnd(8_000, 10_000, 8_000)).toBe(true);
  });

  it('never reaches the end of a film of unknown length', () => {
    expect(hasReachedEnd(5_000, 0)).toBe(false);
    expect(hasReachedEnd(5_000, undefined)).toBe(false);
  });
});

describe('tracks: labels', () => {
  it('names an audio stream by title, else by language and channels', () => {
    expect(audioLabel({ title: 'Commentary', codec: 'AAC' })).toEqual({
      name: 'Commentary',
      detail: 'AAC',
    });
    expect(audioLabel({ language: 'English', channels: '5.1', codec: 'DTS' })).toEqual({
      name: 'English 5.1',
      detail: 'DTS',
    });
    expect(audioLabel({ language: 'French' })).toEqual({ name: 'French', detail: '' });
  });

  it('joins name and codec for the detail page picker', () => {
    expect(audioText({ language: 'English', channels: '5.1', codec: 'DTS' })).toBe(
      'English 5.1 · DTS',
    );
    expect(audioText({ language: 'English' })).toBe('English');
  });

  it('labels subtitles by title, language, or a generic name, with forced marked', () => {
    expect(subtitleLabel({ title: 'SDH' })).toBe('SDH');
    expect(subtitleLabel({ language: 'English' })).toBe('English');
    expect(subtitleLabel({})).toBe('Subtitles');
    expect(subtitleLabel({ language: 'English', forced: true })).toBe('English (forced)');
  });

  it('identifies a stream by id, else by index (including index 0)', () => {
    expect(streamKey({ id: 7, index: 1 })).toBe(7);
    expect(streamKey({ index: 0 })).toBe(0);
  });

  it('stays off when the current subtitle is not in the list', () => {
    expect(nextSubtitle([{ id: 1, language: 'English' }], 'gone')).toBe('off');
    expect(nextSubtitle([{ id: 1, language: 'English' }], SUBTITLE_OFF)).toBe(1);
    expect(nextSubtitle(undefined)).toBe('off');
  });
});

describe('tracks: quality', () => {
  it('formats bitrates as whole or one-decimal Mbps and blank when unknown', () => {
    expect(formatBitrate(8_000_000)).toBe('8 Mbps');
    expect(formatBitrate(1_500_000)).toBe('1.5 Mbps');
    expect(formatBitrate(0)).toBe('');
    expect(formatBitrate(undefined)).toBe('');
  });

  it('names qualities', () => {
    expect(qualityName('auto')).toBe('Auto');
    expect(qualityName('original')).toBe('Original');
    expect(qualityName('720p')).toBe('720p');
    expect(currentQualityLabel('original')).toBe('Original');
    expect(currentQualityLabel()).toBe('Auto');
  });

  it('labels the Original row by width, so a letterboxed file reads 1080p', () => {
    expect(qualityDetail({ id: 'original', bitrate: 8_000_000 }, { width: 1920 })).toBe(
      '1080p · 8 Mbps',
    );
  });

  it('shows the file resolution (by width) only on the Original row, and only when known', () => {
    expect(qualityDetail({ id: 'original', bitrate: 24_000_000 })).toBe('24 Mbps');
    expect(qualityDetail({ id: '720p', bitrate: 4_000_000 }, { width: 1920 })).toBe('4 Mbps');
    expect(qualityDetail({ id: '720p' })).toBe('');
    expect(qualityDetail({ id: '720p', premade: true })).toBe('pre-made');
  });

  it('the Auto row names the rung playing now, the original until told otherwise', () => {
    expect(qualityDetail({ id: 'auto' })).toBe('now Original');
    expect(qualityDetail({ id: 'auto' }, { activeRung: '480p' })).toBe('now 480p');
    expect(qualityDetail({ id: 'auto' }, { activeRung: 'original' })).toBe('now Original');
  });
});
