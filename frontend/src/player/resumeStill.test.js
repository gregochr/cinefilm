import { describe, expect, it } from 'vitest';
import {
  STILL_FADE_MS,
  STILL_HIDE_EVENTS,
  initialStill,
  stillReducer,
  stillRendered,
} from './resumeStill.js';

describe('the resume still', () => {
  it('exists only when resuming above 0', () => {
    expect(initialStill(0)).toBe('none');
    expect(initialStill(-5)).toBe('none');
    expect(initialStill(1)).toBe('pending');
    expect(initialStill(3_000_000)).toBe('pending');
  });

  it('is shown once it has loaded', () => {
    expect(stillReducer('pending', 'loaded')).toBe('shown');
  });

  it('is never shown when it fails to load', () => {
    expect(stillReducer('pending', 'errored')).toBe('none');
    expect(stillRendered('none')).toBe(false);
  });

  it('gives up when it errors after showing', () => {
    expect(stillReducer('shown', 'errored')).toBe('none');
  });

  it.each(STILL_HIDE_EVENTS)('fades out on %s once shown', (event) => {
    expect(stillReducer('shown', event)).toBe('hiding');
  });

  it.each(STILL_HIDE_EVENTS)('is dropped at once on %s while still loading', (event) => {
    expect(stillReducer('pending', event)).toBe('gone');
  });

  it('ignores a load that completes after it was hidden', () => {
    expect(stillReducer('hiding', 'loaded')).toBe('hiding');
    expect(stillReducer('gone', 'loaded')).toBe('gone');
    expect(stillReducer('none', 'loaded')).toBe('none');
  });

  it('is removed when the fade ends and not before', () => {
    expect(stillReducer('hiding', 'faded')).toBe('gone');
    expect(stillReducer('shown', 'faded')).toBe('shown');
    expect(stillReducer('pending', 'faded')).toBe('pending');
  });

  it('never comes back once gone or none, whatever happens', () => {
    for (const event of [...STILL_HIDE_EVENTS, 'loaded', 'errored', 'faded']) {
      expect(stillReducer('gone', event)).toBe('gone');
      expect(stillReducer('none', event)).toBe('none');
    }
  });

  it('does not change on an unknown event, and a later hide while hiding changes nothing', () => {
    expect(stillReducer('shown', 'bogus')).toBe('shown');
    expect(stillReducer('hiding', 'playing')).toBe('hiding');
    expect(stillReducer('hiding', 'errored')).toBe('hiding');
  });

  it('is in the page while pending, shown and hiding only', () => {
    expect(['none', 'pending', 'shown', 'hiding', 'gone'].map(stillRendered)).toEqual([
      false,
      true,
      true,
      true,
      false,
    ]);
  });

  it('fades for 200 ms', () => {
    expect(STILL_FADE_MS).toBe(200);
  });
});
