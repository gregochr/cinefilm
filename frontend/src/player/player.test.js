import { describe, expect, it } from 'vitest';
import { canAutoHide } from './autohide.js';
import { pickFullscreenStrategy } from './fullscreen.js';
import { keyToAction, resolveEscape } from './keymap.js';
import { endThresholdMs, hasReachedEnd, resumePosition } from './resume.js';
import {
  formatClock,
  formatLeft,
  formatRemaining,
  formatResume,
  formatRuntime,
  remainingMs,
  spokenTime,
} from './time.js';
import { nextSubtitle, qualityDetail, subtitleOptions } from './tracks.js';

describe('time formatting', () => {
  it('formats the mono clock and resume label', () => {
    expect(formatClock(3871000)).toBe('01:04:31');
    expect(formatResume(3871000)).toBe('1:04:31');
    expect(formatResume(271000)).toBe('4:31');
  });

  it('computes remaining time with a true minus sign', () => {
    expect(remainingMs(3871000, 10200000)).toBe(6329000);
    expect(formatRemaining(3871000, 10200000)).toBe('−01:45:29');
    expect(remainingMs(11000000, 10200000)).toBe(0);
  });

  it('formats runtimes and time left', () => {
    expect(formatRuntime(10200000)).toBe('2h 50m');
    expect(formatRuntime(2880000)).toBe('48m');
    expect(formatLeft(3871000, 10200000)).toBe('1h 45m left');
  });

  it('speaks times for aria-valuetext', () => {
    expect(spokenTime(3871000)).toBe('1 hour 4 minutes 31 seconds');
    expect(spokenTime(3661000)).toBe('1 hour 1 minute 1 second');
    expect(spokenTime(61000)).toBe('1 minute 1 second');
    expect(spokenTime(0)).toBe('0 seconds');
  });
});

describe('resume', () => {
  it('rewinds 10 s and clamps at 0', () => {
    expect(resumePosition(3871000)).toBe(3861000);
    expect(resumePosition(4000)).toBe(0);
    expect(resumePosition(0)).toBe(0);
  });

  it('ends at the credits marker, else 97%', () => {
    expect(endThresholdMs(1000000, 900000)).toBe(900000);
    expect(endThresholdMs(1000000, null)).toBe(970000);
    expect(hasReachedEnd(969999, 1000000, null)).toBe(false);
    expect(hasReachedEnd(970000, 1000000, null)).toBe(true);
    expect(hasReachedEnd(500000, 1000000, 400000)).toBe(true);
  });
});

describe('keyboard map', () => {
  const key = (k, extra = {}) => keyToAction({ key: k, ...extra });
  it('maps the handover keys', () => {
    expect(key(' ')).toEqual({ type: 'togglePlay' });
    expect(key('ArrowLeft')).toEqual({ type: 'seek', seconds: -10 });
    expect(key('ArrowRight')).toEqual({ type: 'seek', seconds: 10 });
    expect(key('ArrowUp').type).toBe('volume');
    expect(key('ArrowDown').delta).toBeLessThan(0);
    expect(key('f')).toEqual({ type: 'fullscreen' });
    expect(key('a')).toEqual({ type: 'openAudio' });
    expect(key('q')).toEqual({ type: 'openQuality' });
    expect(key('Escape')).toEqual({ type: 'escape' });
  });

  it('C cycles captions only when the film has subtitles', () => {
    expect(keyToAction({ key: 'c' }, { hasSubtitles: true })).toEqual({ type: 'cycleCaptions' });
    expect(keyToAction({ key: 'c' }, { hasSubtitles: false })).toBeNull();
  });

  it('ignores modified keys and unknown keys', () => {
    expect(key('f', { metaKey: true })).toBeNull();
    expect(key('x')).toBeNull();
  });

  it('Esc closes the menu, then exits fullscreen, then dismisses the end card', () => {
    expect(resolveEscape({ menuOpen: true, fullscreen: true, endOverlay: true })).toBe('closeMenu');
    expect(resolveEscape({ menuOpen: false, fullscreen: true, endOverlay: true })).toBe(
      'exitFullscreen',
    );
    expect(resolveEscape({ menuOpen: false, fullscreen: false, endOverlay: true })).toBe(
      'dismissEnd',
    );
    expect(resolveEscape({ menuOpen: false, fullscreen: false, endOverlay: false })).toBe('none');
  });
});

describe('Esc with the network question showing', () => {
  const state = { menuOpen: false, question: false, fullscreen: false, endOverlay: false };

  it('closes a menu before the question', () => {
    expect(resolveEscape({ ...state, menuOpen: true, question: true, fullscreen: true })).toBe(
      'closeMenu',
    );
  });

  it('dismisses the question before exiting fullscreen or dismissing the end card', () => {
    expect(resolveEscape({ ...state, question: true, fullscreen: true, endOverlay: true })).toBe(
      'dismissQuestion',
    );
    expect(resolveEscape({ ...state, question: true })).toBe('dismissQuestion');
  });

  it('goes on to fullscreen, then the end card, then nothing, when there is no question', () => {
    expect(resolveEscape({ ...state, fullscreen: true, endOverlay: true })).toBe('exitFullscreen');
    expect(resolveEscape({ ...state, endOverlay: true })).toBe('dismissEnd');
    expect(resolveEscape(state)).toBe('none');
  });
});

describe('auto-hide rules', () => {
  it('only hides while playing with no menu open', () => {
    expect(canAutoHide({ playing: true, menuOpen: false })).toBe(true);
    expect(canAutoHide({ playing: false, menuOpen: false })).toBe(false);
    expect(canAutoHide({ playing: true, menuOpen: true })).toBe(false);
    expect(canAutoHide({ playing: true, menuOpen: false, ended: true })).toBe(false);
  });
});

describe('fullscreen strategy', () => {
  it('hands off to the native player on iPhone', () => {
    expect(
      pickFullscreenStrategy({
        userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)',
        hasWebkitEnter: true,
        hasRequest: true,
      }),
    ).toBe('native-video');
  });
  it('uses the container elsewhere', () => {
    expect(
      pickFullscreenStrategy({ userAgent: 'Macintosh', hasWebkitEnter: true, hasRequest: true }),
    ).toBe('container');
  });
});

describe('tracks', () => {
  const streams = [
    { id: 1, language: 'English', forced: false },
    { id: 2, language: 'English', forced: true },
  ];
  it('cycles Off → English → English (forced) → Off', () => {
    expect(subtitleOptions(streams).map((o) => o.label)).toEqual([
      'Off',
      'English',
      'English (forced)',
    ]);
    expect(nextSubtitle(streams, 'off')).toBe(1);
    expect(nextSubtitle(streams, 1)).toBe(2);
    expect(nextSubtitle(streams, 2)).toBe('off');
    expect(nextSubtitle([], 'off')).toBe('off');
  });

  it('describes quality rows', () => {
    expect(qualityDetail({ id: 'auto' })).toBe('now Original');
    expect(qualityDetail({ id: 'original', bitrate: 24000000 }, { width: 1920 })).toBe(
      '1080p · 24 Mbps',
    );
    expect(qualityDetail({ id: '480p', bitrate: 1500000, premade: true })).toBe(
      '1.5 Mbps · pre-made',
    );
    expect(qualityDetail({ id: 'auto' }, { activeRung: '720p' })).toBe('now 720p');
  });
});
