import { describe, expect, it } from 'vitest';
import {
  BEAT_GRACE_MS,
  MAX_MINUTES,
  MAX_NUMBER,
  MAX_SAMPLES,
  MAX_STEP_MS,
  createHealth,
} from './health.js';

const OPEN = 5000; // the player route opened here on the injected clock
const CLIENT = { userAgent: 'UA', screen: '1512x982', cores: 8 };

// A counter on a clock the test drives: `at(ms)` sets the time `ms` after the route opened.
function make(over = {}) {
  const clock = { t: OPEN };
  const h = createHealth({
    now: () => clock.t,
    openedAt: OPEN,
    editionId: 11,
    client: CLIENT,
    ...over,
  });
  const at = (ms) => {
    clock.t = OPEN + ms;
  };
  let position = 0;
  // Plays on with a time update every second for `ms` (whole seconds), so the picture keeps moving.
  const playFor = (ms) => {
    for (let spent = 0; spent < ms; spent += 1000) {
      clock.t += 1000;
      position += 1000;
      h.beat(position);
    }
  };
  const report = () => h.snapshot().report;
  return { h, clock, at, playFor, report };
}

// A counter that has had its first frame at `ms`.
function started(ms = 0, over) {
  const m = make(over);
  m.at(ms);
  m.h.playing();
  return m;
}

describe('the report', () => {
  it('is all zeros and nulls before anything happens, and carries the client and the edition', () => {
    const { report } = make();
    expect(report()).toEqual({
      seq: 1,
      editionId: 11,
      elapsedMs: 0,
      environment: 'unknown',
      operator: null,
      latencyMs: null,
      engine: null,
      quality: 'auto',
      autoplayRefused: false,
      startupMs: null,
      watchedMs: 0,
      stallCount: 0,
      stallMs: 0,
      longestStallMs: 0,
      seekCount: 0,
      seekWaitMs: 0,
      reloadCount: 0,
      reloadMs: 0,
      reconnectCount: 0,
      reconnectMs: 0,
      recoveries: 0,
      fellBack: false,
      stepsDown: 0,
      stepsUp: 0,
      netErrors: 0,
      timeouts: 0,
      kbpsLow: null,
      kbpsMedian: null,
      kbpsHigh: null,
      kbpsSamples: 0,
      rungMs: {},
      minutes: [],
      client: { userAgent: 'UA', screen: '1512x982', cores: 8 },
    });
  });

  it('measures elapsed time from the moment the route opened', () => {
    const m = make();
    m.at(3000);
    expect(m.report().elapsedMs).toBe(3000);
  });

  it('records the engine, the quality and the environment it is told', () => {
    const m = make();
    m.h.engine('hlsjs');
    m.h.quality('720p');
    m.h.environment({ environment: 'train', operator: 'Virgin Media Limited', latencyMs: 48 });
    expect(m.report()).toMatchObject({
      engine: 'hlsjs',
      quality: '720p',
      environment: 'train',
      operator: 'Virgin Media Limited',
      latencyMs: 48,
    });
  });

  it('defaults the environment to unknown with no operator and no latency', () => {
    const m = make();
    m.h.environment({ environment: 'home', operator: 'X', latencyMs: 5 });
    m.h.environment();
    expect(m.report()).toMatchObject({ environment: 'unknown', operator: null, latencyMs: null });
    m.h.environment({});
    expect(m.report()).toMatchObject({ environment: 'unknown', operator: null, latencyMs: null });
  });
});

describe('normalising', () => {
  it('rounds every number to a whole number', () => {
    const m = make();
    m.h.environment({ environment: 'home', latencyMs: 12.6 });
    m.h.throughput(910.6);
    expect(m.report()).toMatchObject({ latencyMs: 13, kbpsMedian: 911 });
  });

  it.each([
    [-5, 0],
    [Number.NaN, 0],
    ['abc', 0],
    [0, 0],
    [MAX_NUMBER, MAX_NUMBER],
    [MAX_NUMBER + 1, MAX_NUMBER],
    [3_000_000_000, MAX_NUMBER],
    [Infinity, MAX_NUMBER],
    [-Infinity, 0],
  ])('clamps %s to 0..2,000,000,000 (%s)', (value, expected) => {
    const m = make();
    m.h.environment({ environment: 'home', latencyMs: value });
    expect(m.report().latencyMs).toBe(expected);
  });

  it('cuts the operator at 100 characters and the user agent at 300', () => {
    const m = make({ client: { userAgent: 'u'.repeat(301), screen: '1x1', cores: 2 } });
    m.h.environment({ environment: 'away', operator: 'o'.repeat(101), latencyMs: 1 });
    const { operator, client } = m.report();
    expect(operator).toBe('o'.repeat(100));
    expect(client.userAgent).toBe('u'.repeat(300));
    const exact = make({ client: { userAgent: 'u'.repeat(300), screen: '1x1', cores: 2 } });
    exact.h.environment({ environment: 'away', operator: 'o'.repeat(100) });
    expect(exact.report().operator).toBe('o'.repeat(100));
    expect(exact.report().client.userAgent).toHaveLength(300);
  });

  it('turns a missing user agent into an empty string and a bad screen into 0x0', () => {
    const m = make({ client: { screen: 'wide', cores: 4 } });
    expect(m.report().client).toEqual({ userAgent: '', screen: '0x0', cores: 4 });
    const none = make({ client: undefined });
    expect(none.report().client).toEqual({ userAgent: '', screen: '0x0', cores: null });
  });

  it('keeps a screen of one to five digits either side and rejects six', () => {
    expect(
      make({ client: { userAgent: '', screen: '12345x1', cores: 1 } }).report().client.screen,
    ).toBe('12345x1');
    expect(
      make({ client: { userAgent: '', screen: '123456x1', cores: 1 } }).report().client.screen,
    ).toBe('0x0');
    expect(
      make({ client: { userAgent: '', screen: '1x123456', cores: 1 } }).report().client.screen,
    ).toBe('0x0');
    expect(
      make({ client: { userAgent: '', screen: 'a1x1', cores: 1 } }).report().client.screen,
    ).toBe('0x0');
    expect(
      make({ client: { userAgent: '', screen: '1x1a', cores: 1 } }).report().client.screen,
    ).toBe('0x0');
  });

  it('rounds the cores and sends null where the browser has none', () => {
    expect(
      make({ client: { userAgent: '', screen: '1x1', cores: 8.4 } }).report().client.cores,
    ).toBe(8);
    expect(
      make({ client: { userAgent: '', screen: '1x1', cores: undefined } }).report().client.cores,
    ).toBeNull();
    expect(
      make({ client: { userAgent: '', screen: '1x1', cores: null } }).report().client.cores,
    ).toBeNull();
  });

  it('turns an unknown environment into unknown and a non-string operator into null', () => {
    const m = make();
    m.h.environment({ environment: 'moon', operator: 42, latencyMs: 3 });
    expect(m.report()).toMatchObject({ environment: 'unknown', operator: null, latencyMs: 3 });
  });

  it.each(['home', 'train', 'away', 'unknown'])('accepts the environment %s', (environment) => {
    const m = make();
    m.h.environment({ environment });
    expect(m.report().environment).toBe(environment);
  });

  it('clamps a bad edition id to a number', () => {
    expect(make({ editionId: Number.NaN }).report().editionId).toBe(0);
  });
});

describe('before the first frame', () => {
  it('counts no time and ignores waiting, seeks and source changes, until playing', () => {
    const m = make();
    m.at(1000);
    m.h.waiting();
    m.h.seek();
    m.h.sourceChanged();
    m.h.canPlay(true);
    m.h.paused();
    m.h.ended();
    m.h.seeked();
    m.at(30000);
    const r = m.report();
    expect(r).toMatchObject({
      startupMs: null,
      watchedMs: 0,
      stallCount: 0,
      stallMs: 0,
      seekCount: 0,
      seekWaitMs: 0,
      reloadCount: 0,
      reloadMs: 0,
      minutes: [],
    });
    expect(m.h.started()).toBe(false);
  });

  it('measures the start-up from the route opening to the first playing', () => {
    const m = make();
    m.at(2380);
    m.h.playing();
    expect(m.h.started()).toBe(true);
    expect(m.report().startupMs).toBe(2380);
  });

  it('does not move the start-up on a later playing', () => {
    const m = started(2380);
    m.at(9000);
    m.h.playing();
    expect(m.report().startupMs).toBe(2380);
  });

  it('records time reconnecting even before the first frame', () => {
    const m = make();
    m.h.reconnecting(true);
    m.at(4000);
    m.h.reconnecting(false);
    expect(m.report()).toMatchObject({ reconnectCount: 1, reconnectMs: 4000 });
  });
});

describe('a refused autoplay', () => {
  it('leaves the wait for the viewer out of the start-up', () => {
    const m = make();
    m.at(1000);
    m.h.playRefused();
    m.at(9000);
    m.h.play();
    m.at(9500);
    m.h.playing();
    expect(m.report()).toMatchObject({ autoplayRefused: true, startupMs: 1500 });
  });

  it('keeps the first wait when refused twice', () => {
    const m = make();
    m.at(1000);
    m.h.playRefused();
    m.at(2000);
    m.h.playRefused();
    m.at(5000);
    m.h.play();
    m.at(5500);
    m.h.playing();
    expect(m.report().startupMs).toBe(1500);
  });

  it('ends a wait still open at the first frame', () => {
    const m = make();
    m.at(1000);
    m.h.playRefused();
    m.at(4000);
    m.h.playing();
    expect(m.report().startupMs).toBe(1000);
  });

  it('a play event with no wait open changes nothing', () => {
    const m = make();
    m.at(1000);
    m.h.play();
    m.at(2000);
    m.h.playing();
    expect(m.report()).toMatchObject({ autoplayRefused: false, startupMs: 2000 });
  });

  it('is ignored after the first frame', () => {
    const m = started(1000);
    m.at(2000);
    m.h.playRefused();
    m.at(5000);
    m.h.play();
    expect(m.report()).toMatchObject({ autoplayRefused: false, startupMs: 1000 });
  });
});

describe('start-up and sleep', () => {
  it('leaves a step of more than 150 s before the first frame out of the start-up', () => {
    const m = make();
    m.at(5000);
    m.h.engine('hlsjs');
    m.at(155001); // a sleep
    m.h.engine('native');
    m.at(157001);
    m.h.playing();
    expect(m.report()).toMatchObject({ startupMs: 7000, elapsedMs: 157001 });
  });

  it('counts a step of exactly 150 s before the first frame', () => {
    const m = make();
    m.at(5000);
    m.h.engine('hlsjs');
    m.at(155000);
    m.h.engine('native');
    m.at(157000);
    m.h.playing();
    expect(m.report()).toMatchObject({ startupMs: 157000, elapsedMs: 157000 });
  });

  it('a sleep while waiting for the viewer changes nothing', () => {
    const m = make();
    m.at(2000);
    m.h.playRefused();
    m.at(202000);
    m.h.play();
    m.at(203000);
    m.h.playing();
    expect(m.report()).toMatchObject({ autoplayRefused: true, startupMs: 3000 });
  });

  it('a later playing is not a change: the version stays', () => {
    const m = started(0);
    m.h.paused();
    const v = m.h.version();
    m.h.playing();
    expect(m.h.version()).toBe(v);
  });

  it('leaves both waits out of the start-up when autoplay is refused twice', () => {
    const m = make();
    m.at(1000);
    m.h.playRefused();
    m.at(3000);
    m.h.play();
    m.at(4000);
    m.h.playRefused();
    m.at(9000);
    m.h.play();
    m.at(9500);
    m.h.playing();
    // 1,000 before the first refusal, 1,000 between the waits, 500 after the last.
    expect(m.report().startupMs).toBe(2500);
  });
});

describe('moving', () => {
  it('credits the time between heartbeats to the watched time', () => {
    const m = started(0);
    m.at(1000);
    m.h.beat(1000);
    m.at(2000);
    expect(m.report().watchedMs).toBe(2000);
  });

  it('credits up to 1,500 ms after the last beat that moved, and no more', () => {
    const exact = started(0);
    exact.at(BEAT_GRACE_MS);
    expect(exact.report().watchedMs).toBe(1500);

    const over = started(0);
    over.at(BEAT_GRACE_MS + 1);
    expect(over.report().watchedMs).toBe(1500);

    const later = started(0);
    later.at(1000);
    later.h.beat(1000);
    later.at(5000);
    expect(later.report().watchedMs).toBe(2500);
  });

  it('does not count a beat whose position has not changed as a heartbeat', () => {
    const m = started(0);
    m.at(1000);
    m.h.beat(100);
    m.at(2000);
    m.h.beat(100);
    m.at(2600);
    expect(m.report().watchedMs).toBe(2500);
  });

  it('ignores a position that is not a number', () => {
    const m = started(0);
    m.at(1000);
    m.h.beat(Number.NaN);
    m.at(3000);
    expect(m.report().watchedMs).toBe(1500);
  });

  it('stops counting at pause and at ended', () => {
    const paused = started(0);
    paused.at(1000);
    paused.h.paused();
    paused.at(60000);
    expect(paused.report().watchedMs).toBe(1000);

    const ended = started(0);
    ended.at(1000);
    ended.h.ended();
    ended.at(60000);
    expect(ended.report().watchedMs).toBe(1000);
  });

  it('resumes at the next playing', () => {
    const m = started(0);
    m.at(1000);
    m.h.paused();
    m.at(20000);
    m.h.playing();
    m.at(20800);
    expect(m.report().watchedMs).toBe(1800);
  });

  it('counts nothing across a step of more than 150 s, and counts a step of exactly 150 s', () => {
    const a = started(0);
    a.h.waiting();
    a.at(MAX_STEP_MS);
    expect(a.report().stallMs).toBe(150000);

    const b = started(0);
    b.h.waiting();
    b.at(MAX_STEP_MS + 1);
    expect(b.report()).toMatchObject({ stallMs: 0, stallCount: 1 });
    b.at(MAX_STEP_MS + 1001);
    expect(b.report().stallMs).toBe(1000);
  });

  it('counts nothing when the clock does not advance or goes back', () => {
    const m = started(1000);
    m.h.waiting();
    m.at(1000);
    expect(m.report().stallMs).toBe(0);
    m.at(500);
    expect(m.report().stallMs).toBe(0);
    m.at(1500);
    expect(m.report().stallMs).toBe(1000); // a step back is dropped; the next one counts from there
  });
});

describe('a stall', () => {
  it('runs from waiting to playing', () => {
    const m = started(0);
    m.at(100);
    m.h.waiting();
    m.at(2100);
    m.h.playing();
    m.at(60000);
    expect(m.report()).toMatchObject({
      stallCount: 1,
      stallMs: 2000,
      longestStallMs: 2000,
      watchedMs: 1600, // 100 before the stall, and 1,500 after it on the last beat
    });
  });

  it('does not count a second waiting inside the same stall', () => {
    const m = started(0);
    m.h.waiting();
    m.at(1000);
    m.h.waiting();
    m.at(3000);
    expect(m.report()).toMatchObject({ stallCount: 1, stallMs: 3000, longestStallMs: 3000 });
  });

  it('keeps the longest of several stalls', () => {
    const m = started(0);
    m.h.waiting();
    m.at(2000);
    m.h.playing();
    m.at(3000);
    m.h.waiting();
    m.at(8000);
    m.h.playing();
    m.at(9000);
    m.h.waiting();
    m.at(10000);
    m.h.playing();
    expect(m.report()).toMatchObject({ stallCount: 3, stallMs: 8000, longestStallMs: 5000 });
  });

  it('is ended by a pause, and the next waiting is a new stall', () => {
    const m = started(0);
    m.at(100);
    m.h.waiting();
    m.at(600);
    m.h.paused();
    m.at(5000);
    expect(m.report()).toMatchObject({ stallCount: 1, stallMs: 500 });
    m.h.waiting(); // played on, stalled again
    m.at(5100);
    expect(m.report()).toMatchObject({ stallCount: 2, stallMs: 600, longestStallMs: 500 });
  });

  it('is ended by ended', () => {
    const m = started(0);
    m.h.waiting();
    m.at(500);
    m.h.ended();
    m.at(5000);
    expect(m.report()).toMatchObject({ stallCount: 1, stallMs: 500 });
  });

  it('is split between the two minutes it crosses', () => {
    const m = make();
    m.h.rung('720p', 720);
    m.at(50000);
    m.h.playing();
    m.at(55000);
    m.h.waiting();
    m.at(70000);
    m.h.playing();
    // The minute is 60,000 ms: 55,000 to 60,000 is minute 0, 60,000 to 70,000 minute 1.
    expect(m.report()).toMatchObject({ stallMs: 15000, longestStallMs: 15000, watchedMs: 1500 });
    expect(m.report().minutes).toEqual([
      [0, 0, 3, 1500, 5000, 0, 0],
      [1, 0, 3, 0, 10000, 0, 0],
    ]);
  });
});

describe('a seek', () => {
  it('counts the viewer wait from seek to seeked', () => {
    const m = started(0);
    m.at(1000);
    m.h.seek();
    m.at(2500);
    m.h.seeked();
    m.at(9000);
    expect(m.report()).toMatchObject({ seekCount: 1, seekWaitMs: 1500 });
  });

  it('is ended by playing as well', () => {
    const m = started(0);
    m.at(1000);
    m.h.seek();
    m.at(2000);
    m.h.playing();
    m.at(9000);
    expect(m.report()).toMatchObject({ seekCount: 1, seekWaitMs: 1000 });
  });

  it('ends a stall and counts the wait as seek wait', () => {
    const m = started(0);
    m.at(100);
    m.h.waiting();
    m.at(600);
    m.h.seek();
    m.at(1600);
    m.h.seeked();
    m.at(9000);
    expect(m.report()).toMatchObject({
      stallCount: 1,
      stallMs: 500,
      seekCount: 1,
      seekWaitMs: 1000,
    });
  });

  it('a waiting during a seek is not a stall', () => {
    const m = started(0);
    m.at(100);
    m.h.seek();
    m.at(200);
    m.h.waiting();
    m.at(1200);
    m.h.seeked();
    expect(m.report()).toMatchObject({ stallCount: 0, stallMs: 0, seekWaitMs: 1100 });
  });

  it('every seek counts', () => {
    const m = started(0);
    m.h.seek();
    m.h.seek();
    expect(m.report().seekCount).toBe(2);
  });

  it('is not watched time when seeked comes with no waiting, and moving counts again after it', () => {
    const m = make();
    m.h.rung('720p', 720);
    m.h.playing();
    m.at(1000);
    m.h.beat(1000);
    m.at(2000);
    m.h.beat(2000);
    m.h.seek();
    m.at(2400);
    m.h.seeked();
    expect(m.report()).toMatchObject({
      seekCount: 1,
      seekWaitMs: 400,
      watchedMs: 2000,
      rungMs: { '720p': 2000 },
    });
    expect(m.report().minutes).toEqual([[0, 0, 3, 2000, 0, 0, 0]]);
    m.at(2650);
    m.h.beat(3000);
    expect(m.report()).toMatchObject({ watchedMs: 2250, rungMs: { '720p': 2250 } });
    expect(m.report().minutes).toEqual([[0, 0, 3, 2250, 0, 0, 0]]);
  });

  it('credits the first beat interval after a seek wait longer than the grace', () => {
    const m = started(0);
    m.h.seek();
    m.at(2000);
    m.h.seeked();
    m.at(2250);
    m.h.beat(5000);
    expect(m.report()).toMatchObject({ seekWaitMs: 2000, watchedMs: 250 });
  });

  it('a seeked with no seek before it does not restart the heartbeat', () => {
    const m = started(0);
    m.at(1000);
    m.h.beat(1000);
    m.at(4000);
    m.h.seeked(); // the player’s own restoring seek
    m.at(5000);
    expect(m.report()).toMatchObject({ seekCount: 0, watchedMs: 2500 });
  });

  it('a seek while paused credits no moving time before or after, until playing', () => {
    const m = started(0);
    m.h.paused();
    m.at(1000);
    m.h.seek();
    m.at(1500);
    m.h.seeked();
    m.at(9000);
    expect(m.report()).toMatchObject({ seekCount: 1, seekWaitMs: 500, watchedMs: 0 });
    m.at(10000);
    m.h.playing();
    m.at(10500);
    expect(m.report().watchedMs).toBe(500);
  });

  it('a waiting then playing during a counted seek is seek wait only: no stall, nothing watched', () => {
    const m = started(0);
    m.at(1000);
    m.h.beat(1000);
    m.h.seek();
    m.at(1200);
    m.h.waiting();
    m.at(2000);
    m.h.playing();
    expect(m.report()).toMatchObject({
      stallCount: 0,
      stallMs: 0,
      seekCount: 1,
      seekWaitMs: 1000,
      watchedMs: 1000,
    });
    m.at(2500);
    expect(m.report().watchedMs).toBe(1500);
  });
});

describe('a seek made outside the controls (the element’s seeking event)', () => {
  const on = (engine) => {
    const m = make();
    m.h.engine(engine);
    m.at(0);
    m.h.playing();
    m.at(1000);
    m.h.beat(1000);
    return m;
  };

  it.each(['native', 'direct'])(
    'counts one seek and its wait, none of it watched, on %s',
    (engine) => {
      const m = on(engine);
      m.h.seeking();
      m.at(1800);
      m.h.seeked();
      expect(m.report()).toMatchObject({ seekCount: 1, seekWaitMs: 800, watchedMs: 1000 });
    },
  );

  it('counts nothing on hls.js, which corrects the position itself, and the time is watched', () => {
    const m = on('hlsjs');
    m.h.seeking();
    m.at(1800);
    m.h.seeked();
    expect(m.report()).toMatchObject({ seekCount: 0, seekWaitMs: 0, watchedMs: 1800 });
  });

  it('counts a seek once when seek() has already opened it', () => {
    const m = on('native');
    m.h.seek();
    m.h.seeking();
    m.at(1800);
    m.h.seeked();
    expect(m.report()).toMatchObject({ seekCount: 1, seekWaitMs: 800 });
  });

  it('counts again for a second seek after the first has ended', () => {
    const m = on('native');
    m.h.seeking();
    m.at(1500);
    m.h.seeked();
    m.h.seeking();
    expect(m.report().seekCount).toBe(2);
  });

  it('counts nothing before the first frame or during a reload', () => {
    const before = make();
    before.h.engine('native');
    before.at(500);
    before.h.seeking();
    expect(before.report().seekCount).toBe(0);

    const m = on('native');
    m.h.sourceChanged();
    m.h.seeking();
    m.at(3000);
    expect(m.report()).toMatchObject({ seekCount: 0, seekWaitMs: 0 });
  });

  it('a waiting after an element seek is not a stall on native, and is one on hls.js', () => {
    const native = on('native');
    native.h.seeking();
    native.at(1200);
    native.h.waiting();
    native.at(2000);
    native.h.playing();
    expect(native.report()).toMatchObject({
      stallCount: 0,
      stallMs: 0,
      seekCount: 1,
      seekWaitMs: 1000,
    });

    const hls = on('hlsjs');
    hls.h.seeking();
    hls.at(1200);
    hls.h.waiting();
    hls.at(2000);
    hls.h.playing();
    expect(hls.report()).toMatchObject({ stallCount: 1, stallMs: 800, seekCount: 0 });
  });

  it('ends a stall that is open', () => {
    const m = on('native');
    m.h.waiting();
    m.at(1500);
    m.h.seeking();
    m.at(2500);
    m.h.seeked();
    m.at(9000);
    expect(m.report()).toMatchObject({
      stallCount: 1,
      stallMs: 500,
      seekCount: 1,
      seekWaitMs: 1000,
    });
  });

  it('is a change when it counts', () => {
    const m = on('native');
    m.h.paused();
    const v = m.h.version();
    m.h.seeking();
    expect(m.h.version()).not.toBe(v);
  });

  it('is not a change when it is ignored', () => {
    const m = on('hlsjs');
    m.h.paused();
    const v = m.h.version();
    m.h.seeking();
    expect(m.h.version()).toBe(v);
  });
});

describe('a source change', () => {
  it('stops the picture, starts a reload and counts its time', () => {
    const m = started(0);
    m.at(1000);
    m.h.sourceChanged();
    m.at(4000);
    expect(m.report()).toMatchObject({ reloadCount: 1, reloadMs: 3000, watchedMs: 1000 });
  });

  it('counts one reload however many source changes it takes', () => {
    const m = started(0);
    m.h.sourceChanged();
    m.at(1000);
    m.h.sourceChanged();
    m.at(2000);
    expect(m.report()).toMatchObject({ reloadCount: 1, reloadMs: 2000 });
  });

  it('ends a seek that is open', () => {
    const m = started(0);
    m.at(100);
    m.h.seek();
    m.at(600);
    m.h.sourceChanged();
    m.at(5000);
    expect(m.report()).toMatchObject({ seekCount: 1, seekWaitMs: 500, reloadCount: 1 });
  });

  it('ends a stall that is open', () => {
    const m = started(0);
    m.at(100);
    m.h.waiting();
    m.at(600);
    m.h.sourceChanged();
    m.at(5000);
    expect(m.report()).toMatchObject({ stallCount: 1, stallMs: 500, reloadMs: 4400 });
  });

  it('ignores waiting and seeks during the reload', () => {
    const m = started(0);
    m.h.sourceChanged();
    m.at(1000);
    m.h.waiting();
    m.h.seek();
    m.at(2000);
    expect(m.report()).toMatchObject({
      stallCount: 0,
      stallMs: 0,
      seekCount: 0,
      seekWaitMs: 0,
      reloadMs: 2000,
    });
  });

  it('ends at playing', () => {
    const m = started(0);
    m.h.sourceChanged();
    m.at(2000);
    m.h.playing();
    m.at(5000);
    expect(m.report()).toMatchObject({ reloadCount: 1, reloadMs: 2000 });
  });

  it('ends at canplay while paused, but not at canplay while playing', () => {
    const pausedAtCanPlay = started(0);
    pausedAtCanPlay.h.sourceChanged();
    pausedAtCanPlay.at(2000);
    pausedAtCanPlay.h.canPlay(true);
    pausedAtCanPlay.at(5000);
    expect(pausedAtCanPlay.report().reloadMs).toBe(2000);

    const playingAtCanPlay = started(0);
    playingAtCanPlay.h.sourceChanged();
    playingAtCanPlay.at(2000);
    playingAtCanPlay.h.canPlay(false);
    playingAtCanPlay.at(5000);
    expect(playingAtCanPlay.report().reloadMs).toBe(5000);
  });

  it('a second reload after the first has ended counts again', () => {
    const m = started(0);
    m.h.sourceChanged();
    m.at(1000);
    m.h.playing();
    m.at(2000);
    m.h.sourceChanged();
    expect(m.report().reloadCount).toBe(2);
  });
});

describe('reconnecting', () => {
  it('counts each time the notice begins and how long it shows', () => {
    const m = started(0);
    m.at(1000);
    m.h.reconnecting(true);
    m.at(2000);
    m.h.reconnecting(true);
    m.at(13000);
    m.h.reconnecting(false);
    m.at(20000);
    expect(m.report()).toMatchObject({ reconnectCount: 1, reconnectMs: 12000 });
    m.h.reconnecting(true);
    m.at(21000);
    expect(m.report()).toMatchObject({ reconnectCount: 2, reconnectMs: 13000 });
  });

  it('turning it off when it is not on does nothing', () => {
    const m = started(0);
    m.h.reconnecting(false);
    m.at(1000);
    expect(m.report()).toMatchObject({ reconnectCount: 0, reconnectMs: 0 });
  });
});

describe('rungs and steps', () => {
  it('credits moving time to the rung playing', () => {
    const m = make();
    m.h.rung('480p', 480);
    m.at(0);
    m.h.playing();
    m.playFor(2000);
    m.h.rung('360p', 360);
    m.playFor(3000);
    const r = m.report();
    expect(r.rungMs).toEqual({ '480p': 2000, '360p': 3000 });
    expect(r.watchedMs).toBe(5000);
  });

  it('lists no rung that has had no time, and credits none while no rung is known', () => {
    const m = started(0);
    m.playFor(1000);
    m.h.rung('720p', 720);
    expect(m.report().rungMs).toEqual({});
    expect(m.report().watchedMs).toBe(1000);
  });

  it('credits no rung time while paused', () => {
    const m = make();
    m.h.rung('720p', 720);
    m.h.playing();
    m.playFor(1000);
    m.h.paused();
    m.clock.t += 20000;
    expect(m.report().rungMs).toEqual({ '720p': 1000 });
  });

  it('counts a shorter picture as a step down and a taller one as a step up while Auto', () => {
    const m = make();
    m.h.rung('1080p', 1080);
    m.h.rung('720p', 720);
    m.h.rung('480p', 480);
    m.h.rung('720p', 720);
    expect(m.report()).toMatchObject({ stepsDown: 2, stepsUp: 1 });
  });

  it('counts no step for a pinned quality', () => {
    const m = make();
    m.h.quality('720p');
    m.h.rung('720p', 720);
    m.h.rung('480p', 480);
    m.h.rung('1080p', 1080);
    expect(m.report()).toMatchObject({ stepsDown: 0, stepsUp: 0 });
  });

  it('counts no step when a height is unknown', () => {
    const m = make();
    m.h.rung('720p', 720);
    m.h.rung('original', null);
    m.h.rung('480p', 480);
    m.h.rung('360p', undefined);
    expect(m.report()).toMatchObject({ stepsDown: 0, stepsUp: 0 });
  });

  it('counts no step for the same rung or for a different rung of the same height', () => {
    const m = make();
    m.h.rung('720p', 720);
    m.h.rung('720p', 720);
    m.h.rung('other', 720);
    expect(m.report()).toMatchObject({ stepsDown: 0, stepsUp: 0 });
  });

  it('counts a change that crosses back to Auto as no step: the viewer chose it', () => {
    const m = make();
    m.h.rung('720p', 720);
    m.h.quality('480p');
    m.h.rung('480p', 480);
    m.h.quality('auto');
    m.h.rung('1080p', 1080);
    expect(m.report()).toMatchObject({ stepsDown: 0, stepsUp: 0 });
    m.h.rung('720p', 720);
    expect(m.report().stepsDown).toBe(1);
  });

  it('quality set to what it already is changes nothing', () => {
    const m = make();
    m.h.rung('720p', 720);
    m.h.quality('auto');
    m.h.rung('480p', 480);
    expect(m.report().stepsDown).toBe(1);
  });
});

describe('time before an input is credited to what it replaces', () => {
  it('rung: moving time up to the change goes to the old rung', () => {
    const m = make();
    m.h.rung('720p', 720);
    m.h.playing();
    m.at(1200); // no beat: the grace covers it
    m.h.rung('480p', 480);
    m.at(1500);
    expect(m.report().rungMs).toEqual({ '720p': 1200, '480p': 300 });
  });

  it('a different height under the same rung id is no step', () => {
    const m = make();
    m.h.rung('720p', 720);
    m.h.rung('720p', 480);
    expect(m.report()).toMatchObject({ stepsDown: 0, stepsUp: 0 });
  });

  it('a stalled player with no time passing keeps its version', () => {
    const m = started(0);
    m.h.waiting();
    const v = m.h.version();
    expect(m.h.version()).toBe(v);
  });
});

describe('what else is counted', () => {
  it('counts network errors and timeouts separately', () => {
    const m = make();
    m.h.networkError({ timeout: true });
    m.h.networkError({ timeout: false });
    m.h.networkError({ timeout: false });
    m.h.networkError();
    expect(m.report()).toMatchObject({ timeouts: 1, netErrors: 3 });
  });

  it('counts recoveries, and fell back is a flag', () => {
    const m = make();
    m.h.recovered();
    m.h.recovered();
    m.h.fellBack();
    m.h.fellBack();
    expect(m.report()).toMatchObject({ recoveries: 2, fellBack: true });
  });
});

describe('throughput', () => {
  const samples = (values) => {
    const m = make();
    values.forEach((v) => m.h.throughput(v));
    return m.report();
  };

  it('is null with no samples', () => {
    expect(samples([])).toMatchObject({
      kbpsLow: null,
      kbpsMedian: null,
      kbpsHigh: null,
      kbpsSamples: 0,
    });
  });

  it('is that sample for one', () => {
    expect(samples([900])).toMatchObject({
      kbpsLow: 900,
      kbpsMedian: 900,
      kbpsHigh: 900,
      kbpsSamples: 1,
    });
  });

  it('is the nearest rank for two', () => {
    expect(samples([300, 100])).toMatchObject({
      kbpsLow: 100,
      kbpsMedian: 100,
      kbpsHigh: 300,
      kbpsSamples: 2,
    });
  });

  it('is the 10th, 50th and 90th nearest rank for ten, however they arrive', () => {
    expect(samples([700, 100, 1000, 400, 200, 900, 500, 300, 800, 600])).toMatchObject({
      kbpsLow: 100,
      kbpsMedian: 500,
      kbpsHigh: 900,
      kbpsSamples: 10,
    });
  });

  it('is exact where a floating-point product would round up (30 samples)', () => {
    const values = Array.from({ length: 30 }, (_, i) => (i + 1) * 10);
    // 10th percentile of 30 is rank 3, not 4.
    expect(samples(values)).toMatchObject({ kbpsLow: 30, kbpsMedian: 150, kbpsHigh: 270 });
  });

  it('ignores a value that is not a finite positive number', () => {
    expect(samples([0, -5, Number.NaN, Infinity, '12', null, undefined, 500])).toMatchObject({
      kbpsMedian: 500,
      kbpsSamples: 1,
    });
  });

  it('keeps the newest 4,000 samples', () => {
    const values = Array.from({ length: MAX_SAMPLES + 1 }, (_, i) => i + 1); // 1..4001
    const r = samples(values);
    expect(r.kbpsSamples).toBe(MAX_SAMPLES);
    expect(r.kbpsLow).toBe(401); // the oldest, 1, has gone: 2..4001, rank 400
    expect(r.kbpsHigh).toBe(3601);
  });
});

describe('minutes', () => {
  it('lists the minute of each stretch, in order, with the unfinished one last', () => {
    const m = make();
    m.h.rung('720p', 720);
    m.h.playing();
    m.playFor(30000);
    m.h.throughput(800);
    m.playFor(10000);
    m.h.throughput(1000);
    m.playFor(50000); // to 90 s
    expect(m.report().minutes).toEqual([
      [0, 800, 3, 60000, 0, 0, 0],
      [1, 0, 3, 30000, 0, 0, 0],
    ]);
  });

  it('takes the median of a minute’s own samples by nearest rank', () => {
    const m = make();
    [900, 100, 500].forEach((v) => m.h.throughput(v));
    m.at(65000);
    m.h.throughput(300);
    expect(m.report().minutes).toEqual([
      [0, 500, 0, 0, 0, 0, 0],
      [1, 300, 0, 0, 0, 0, 0],
    ]);
  });

  it('keeps only the newest 4,000 samples of a minute', () => {
    const m = make();
    m.h.throughput(1000); // the oldest, which the cap drops
    for (let i = 0; i < 2000; i += 1) m.h.throughput(1);
    for (let i = 0; i < 2000; i += 1) m.h.throughput(1000);
    // Kept: 2,000 ones and 2,000 thousands, so the median is rank 2,000, a one.
    expect(m.report().minutes).toEqual([[0, 1, 0, 0, 0, 0, 0]]);
  });

  it('lists minutes in order even when the clock went back before a sample', () => {
    const m = make();
    m.at(130000);
    m.h.throughput(300);
    m.at(10000);
    m.h.throughput(100);
    expect(m.report().minutes.map((row) => row[0])).toEqual([0, 2]);
  });

  it('adds nothing to the next minute for a stall that ends exactly on the boundary', () => {
    const m = started(50000);
    m.h.waiting();
    m.at(60000);
    expect(m.report().minutes).toEqual([[0, 0, 0, 0, 10000, 0, 0]]);
  });

  it('puts time that crosses two boundaries in each of three minutes', () => {
    const m = started(50000);
    m.h.waiting();
    m.at(190000);
    expect(m.report().minutes).toEqual([
      [0, 0, 0, 0, 10000, 0, 0],
      [1, 0, 0, 0, 60000, 0, 0],
      [2, 0, 0, 0, 60000, 0, 0],
      [3, 0, 0, 0, 10000, 0, 0],
    ]);
  });

  it('leaves out a minute in which nothing happened', () => {
    const m = make();
    m.h.rung('720p', 720);
    m.h.playing();
    m.playFor(1000);
    m.h.paused();
    m.at(400000); // a sleep, then play on in minute 6
    m.h.playing();
    m.playFor(2000);
    expect(m.report().minutes).toEqual([
      [0, 0, 3, 1000, 0, 0, 0],
      [6, 0, 3, 2000, 0, 0, 0],
    ]);
  });

  it('codes a minute that was all stall by the rung being played', () => {
    const m = make();
    m.h.rung('480p', 480);
    m.at(50000);
    m.h.playing();
    m.h.waiting();
    m.at(130000);
    expect(m.report().minutes).toEqual([
      [0, 0, 4, 0, 10000, 0, 0],
      [1, 0, 4, 0, 60000, 0, 0],
      [2, 0, 4, 0, 10000, 0, 0],
    ]);
  });

  it('codes a stalled minute 0 when no rung is known', () => {
    const m = started(0);
    m.h.waiting();
    m.at(5000);
    expect(m.report().minutes).toEqual([[0, 0, 0, 0, 5000, 0, 0]]);
  });

  it('lists a minute that has only a sample', () => {
    const m = make();
    m.at(130000);
    m.h.throughput(700);
    expect(m.report().minutes).toEqual([[2, 700, 0, 0, 0, 0, 0]]);
  });

  it.each([
    ['original', 1],
    ['1080p', 2],
    ['720p', 3],
    ['480p', 4],
    ['360p', 5],
    ['240p', 6],
    ['mystery', 0],
  ])('codes rung %s as %i', (id, code) => {
    const m = make();
    m.h.rung(id, 100);
    m.h.playing();
    m.playFor(1000);
    expect(m.report().minutes).toEqual([[0, 0, code, 1000, 0, 0, 0]]);
  });

  it('codes a minute by the rung last credited in it', () => {
    const m = make();
    m.h.rung('720p', 720);
    m.h.playing();
    m.playFor(30000);
    m.h.rung('480p', 480);
    m.playFor(20000);
    expect(m.report().minutes).toEqual([[0, 0, 4, 50000, 0, 0, 0]]);
  });

  it('adds no minute once there are 360, though the totals keep counting', () => {
    const m = make();
    for (let n = 0; n < MAX_MINUTES + 40; n += 1) {
      m.at(n * 60000 + 1);
      m.h.throughput(100 + n);
    }
    m.at((MAX_MINUTES + 40) * 60000 + 1);
    m.h.playing();
    m.playFor(2000);
    const r = m.report();
    expect(r.minutes).toHaveLength(MAX_MINUTES);
    expect(r.minutes[0]).toEqual([0, 100, 0, 0, 0, 0, 0]);
    expect(r.minutes[MAX_MINUTES - 1]).toEqual([
      MAX_MINUTES - 1,
      100 + MAX_MINUTES - 1,
      0,
      0,
      0,
      0,
      0,
    ]);
    expect(r.kbpsSamples).toBe(MAX_MINUTES + 40);
    expect(r.watchedMs).toBe(2000);
    expect(r.rungMs).toEqual({});
  });

  it('takes 360 minutes in full', () => {
    const m = make();
    for (let n = 0; n < MAX_MINUTES; n += 1) {
      m.at(n * 60000 + 1);
      m.h.throughput(100);
    }
    expect(m.report().minutes).toHaveLength(MAX_MINUTES);
  });
});

describe('buffered ahead and the rung being fetched', () => {
  it('keeps the last figure a minute was given, as whole seconds', () => {
    const m = make();
    m.at(1000);
    m.h.beat(1000, 30);
    m.at(2000);
    m.h.beat(2000, 12.6);
    expect(m.report().minutes).toEqual([[0, 0, 0, 0, 0, 13, 0]]);
  });

  it('is 0 for a minute in which no beat carried a figure', () => {
    const m = make();
    m.at(1000);
    m.h.beat(1000, 30);
    m.at(61000);
    m.h.beat(2000);
    m.h.throughput(500);
    expect(m.report().minutes).toEqual([
      [0, 0, 0, 0, 0, 30, 0],
      [1, 500, 0, 0, 0, 0, 0],
    ]);
  });

  it.each([
    ['NaN', Number.NaN],
    ['undefined', undefined],
    ['a negative number', -3],
    ['a string', '20'],
    ['Infinity', Number.POSITIVE_INFINITY],
  ])('ignores %s and leaves the earlier figure', (_name, value) => {
    const m = make();
    m.at(1000);
    m.h.beat(1000, 20);
    m.at(2000);
    m.h.beat(2000, value);
    expect(m.report().minutes).toEqual([[0, 0, 0, 0, 0, 20, 0]]);
  });

  it('takes 0 as a figure, replacing an earlier one', () => {
    const m = make();
    m.at(1000);
    m.h.beat(1000, 20);
    m.at(2000);
    m.h.beat(2000, 0);
    expect(m.report().minutes).toEqual([[0, 0, 0, 0, 0, 0, 0]]);
  });

  it('records the figure even when the position has not moved', () => {
    const m = make();
    m.at(1000);
    m.h.beat(5000, 10);
    m.at(2000);
    m.h.beat(5000, 44);
    expect(m.report().minutes).toEqual([[0, 0, 0, 0, 0, 44, 0]]);
  });

  it('records the figure when the position is not a number', () => {
    const m = make();
    m.at(1000);
    m.h.beat(Number.NaN, 8);
    expect(m.report().minutes).toEqual([[0, 0, 0, 0, 0, 8, 0]]);
  });

  it('puts the rung being fetched in a minute where time was credited', () => {
    const m = make();
    m.h.rung('720p', 720);
    m.h.fetching('480p');
    m.h.playing();
    m.playFor(2000);
    expect(m.report().minutes).toEqual([[0, 0, 3, 2000, 0, 0, 4]]);
  });

  it('puts the rung being fetched in a minute that only had a speed sample', () => {
    const m = make();
    m.h.fetching('360p');
    m.at(61000);
    m.h.throughput(900);
    expect(m.report().minutes).toEqual([[1, 900, 0, 0, 0, 0, 5]]);
  });

  it('is 0 before any fetching call and for an id outside the ladder', () => {
    const m = make();
    m.h.playing();
    m.playFor(1000);
    m.h.fetching('mystery');
    m.at(61000);
    m.h.throughput(100);
    expect(m.report().minutes).toEqual([
      [0, 0, 0, 2500, 0, 0, 0],
      [1, 100, 0, 0, 0, 0, 0],
    ]);
  });

  it.each(['direct', 'native'])('is forgotten once the engine is %s', (kind) => {
    const m = make();
    m.h.rung('720p', 720);
    m.h.fetching('480p');
    m.h.playing();
    m.playFor(2000);
    m.h.paused(); // closes the minute's credit before the switch
    m.h.engine(kind);
    m.at(61000);
    m.h.playing();
    m.h.throughput(100);
    m.playFor(2000);
    const rows = m.report().minutes;
    expect(rows[0][6]).toBe(4);
    expect(rows[1][6]).toBe(0);
  });

  it('is kept when the engine is hlsjs', () => {
    const m = make();
    m.h.fetching('480p');
    m.h.engine('hlsjs');
    m.h.playing();
    m.playFor(2000);
    expect(m.report().minutes[0][6]).toBe(4);
  });

  it('is not a change of the report by itself', () => {
    const m = make();
    const before = m.h.version();
    m.h.fetching('480p');
    expect(m.h.version()).toBe(before);
  });

  it('gives every row seven figures', () => {
    const m = make();
    m.h.rung('720p', 720);
    m.h.fetching('480p');
    m.h.playing();
    m.playFor(3000);
    m.h.beat(9000, 5);
    m.at(120000);
    m.h.throughput(10);
    for (const row of m.report().minutes) expect(row).toHaveLength(7);
  });

  it('still stops at 360 minutes when the beats carry figures', () => {
    const m = make();
    for (let n = 0; n < MAX_MINUTES + 5; n += 1) {
      m.at(n * 60000 + 1);
      m.h.beat(n, 7);
    }
    const { minutes } = m.report();
    expect(minutes).toHaveLength(MAX_MINUTES);
    expect(minutes[MAX_MINUTES - 1]).toEqual([MAX_MINUTES - 1, 0, 0, 0, 0, 7, 0]);
  });
});

describe('seq and version', () => {
  it('raises seq with every snapshot, even when nothing is new', () => {
    const m = make();
    expect(m.h.snapshot().report.seq).toBe(1);
    expect(m.h.snapshot().report.seq).toBe(2);
    expect(m.h.snapshot().report.seq).toBe(3);
  });

  it('keeps the version while nothing happens and no time accrues', () => {
    const m = make();
    const v = m.h.version();
    m.at(10000);
    m.h.snapshot();
    m.at(20000);
    expect(m.h.version()).toBe(v);
    expect(m.h.snapshot().version).toBe(v);
  });

  it('reports the version at the snapshot', () => {
    const m = make();
    m.h.engine('hlsjs');
    const { version } = m.h.snapshot();
    expect(m.h.version()).toBe(version);
  });

  it('changes as time accrues while the picture moves, and stops when the beat goes stale', () => {
    const m = started(0);
    const first = m.h.version();
    m.at(1000);
    m.h.beat(1000);
    const second = m.h.version();
    expect(second).not.toBe(first);
    m.at(2500);
    const third = m.h.version();
    expect(third).not.toBe(second);
    m.at(30000);
    expect(m.h.version()).toBe(third);
    m.at(60000);
    expect(m.h.version()).toBe(third);
  });

  it('keeps the version for a paused player however long it sits', () => {
    const m = started(0);
    m.h.paused();
    const v = m.h.version();
    m.at(100000);
    expect(m.h.version()).toBe(v);
  });

  it.each([
    ['the first frame', (m) => m.h.playing()],
    ['a refused autoplay', (m) => m.h.playRefused()],
    ['a quality', (m) => m.h.quality('720p')],
    ['an engine', (m) => m.h.engine('hlsjs')],
    ['an environment', (m) => m.h.environment({ environment: 'home' })],
    ['an operator', (m) => m.h.environment({ environment: 'unknown', operator: 'X' })],
    ['a latency', (m) => m.h.environment({ environment: 'unknown', latencyMs: 4 })],
    ['a reconnect', (m) => m.h.reconnecting(true)],
    ['a recovery', (m) => m.h.recovered()],
    ['a fall back', (m) => m.h.fellBack()],
    ['a timeout', (m) => m.h.networkError({ timeout: true })],
    ['a network error', (m) => m.h.networkError({ timeout: false })],
    ['a sample', (m) => m.h.throughput(100)],
  ])('changes for %s', (_name, act) => {
    const m = make();
    const before = m.h.version();
    act(m);
    expect(m.h.version()).not.toBe(before);
  });

  it.each([
    ['a stall', (m) => m.h.waiting()],
    ['a seek', (m) => m.h.seek()],
    ['a source change', (m) => m.h.sourceChanged()],
  ])('changes for %s after the first frame', (_name, act) => {
    const m = started(0);
    const before = m.h.version();
    act(m);
    expect(m.h.version()).not.toBe(before);
  });

  it('changes for a step in either direction', () => {
    const down = make();
    down.h.rung('1080p', 1080);
    const a = down.h.version();
    down.h.rung('720p', 720);
    expect(down.h.version()).not.toBe(a);
    const up = make();
    up.h.rung('720p', 720);
    const b = up.h.version();
    up.h.rung('1080p', 1080);
    expect(up.h.version()).not.toBe(b);
  });

  it('keeps the version when a value is set to what it already is, or a sample is invalid', () => {
    const m = started(0);
    m.h.paused();
    m.h.engine('hlsjs');
    m.h.quality('720p');
    m.h.environment({ environment: 'home', operator: 'X', latencyMs: 4 });
    m.h.fellBack();
    m.h.rung('720p', 720);
    const v = m.h.version();
    m.h.engine('hlsjs');
    m.h.quality('720p');
    m.h.environment({ environment: 'home', operator: 'X', latencyMs: 4 });
    m.h.fellBack();
    m.h.reconnecting(false);
    m.h.throughput(0);
    m.h.rung('720p', 720);
    m.h.rung('other', 720);
    m.h.beat(5);
    m.h.paused();
    m.h.canPlay(true);
    m.h.seeked();
    m.h.play();
    expect(m.h.version()).toBe(v);
  });

  it('keeps the version for a refused autoplay said twice', () => {
    const m = make();
    m.h.playRefused();
    const v = m.h.version();
    m.h.playRefused();
    expect(m.h.version()).toBe(v);
  });

  it('changes as stall, seek, reload and reconnect time accrues', () => {
    const stall = started(0);
    stall.h.waiting();
    const a = stall.h.version();
    stall.at(1000);
    expect(stall.h.version()).not.toBe(a);

    const seek = started(0);
    seek.h.paused();
    seek.h.seek();
    const b = seek.h.version();
    seek.at(1000);
    expect(seek.h.version()).not.toBe(b);

    const reload = started(0);
    reload.h.sourceChanged();
    const c = reload.h.version();
    reload.at(1000);
    expect(reload.h.version()).not.toBe(c);

    const reconnect = started(0);
    reconnect.h.paused();
    reconnect.h.reconnecting(true);
    const d = reconnect.h.version();
    reconnect.at(1000);
    expect(reconnect.h.version()).not.toBe(d);
  });
});
