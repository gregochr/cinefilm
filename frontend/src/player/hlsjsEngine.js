// Adaptive playback through hls.js (Chrome, Firefox). hls.js owns the buffer and the level choice; this adds
// the resilience rules from the transcoding plan: retries instead of failures, "Reconnecting…" with one
// restart 5 s after each fatal network error (at once when the browser comes back online), a new session when the old one has expired, and Auto stepping up one level only after ~60 s. The step gate follows the level hls.js
// starts to FETCH (LEVEL_SWITCHING), so a step down caps Auto at once; LEVEL_SWITCHED, the level on screen,
// only reports what is playing. The cap rises only while hls.js is fetching at the cap, and a fragment that
// times out or gets no answer pulls it down to the level being fetched. While the buffer is deep (floorHold.js)
// Auto may not go below 360p on a ladder with a 240p bottom rung: `minAutoBitrate` is raised to the 360p level,
// but only once hls.js is fetching at 360p or above and not after fragment trouble (a floor, not a pull). The
// hold reads the engine's own fragment rates, timed from `loading.start`, and the rate of the fragment in flight,
// re-read every HOLD_CHECK_MS (floor-hold-by-time-plan.md). Above 480p Auto climbs only on headroom
// (champagne.js, step-up-bank-plan.md): the cap is the lower of the step gate and that ceiling, worked out in
// one place (`applyCap`) whatever the ladder. When the bank is full Auto may throw away the film from 300 s ahead
// and fetch it again one rung higher, up to 480p (rebank.js, step-up-bank-plan.md): `minAutoBitrate` is raised to
// that rung, then the buffer is flushed from a fragment start. `writeFloor` is the only writer of that setting:
// the larger of the hold's floor and the re-bank's, and the re-bank starts only once hls.js is fetching the new
// rung. hls.js does not choose a level again after one of its fragments failed until a fragment of that level
// loads, so in Auto a mark that has stood a minute without trouble on a level not being fetched is forgotten
// (rungMarks.js, stuck-rung-plan.md). While the film bank is on (outside-bank-plan.md, decision 3) hls.js plays
// on a manual level, the rung the bank holds its next fragment at, and every rule above is suspended: the engine's
// `bankOn`, `bankWrongRung` and `bankRelease` are how the bank drives that. `bankInput` is the look-ahead's input:
// it arms the bank, turns it on and off and times the front's rung choice (decisions 2, 6, 7 and 8); with the
// rung pinned, or while no stretch is near, none of it runs and the loaders pass everything through.
import { FRONT_MAX_HEIGHT, createBankFront } from './bankFront.js';
import { createBankLoaders } from './bankLoader.js';
import { BANK_RUNG_TEXT, createBankRequests } from './bankRequests.js';
import { MIN_MEASURED_S, chooseBankRung } from './bankRung.js';
import { bankedAhead } from './buffered.js';
import { champagneBase, champagneCeiling } from './champagne.js';
import { createFloorHold } from './floorHold.js';
import { rungForLevel } from './ladder.js';
import { RECONNECT_MS } from './reconnect.js';
import { createNoProgressLoader } from './noProgressLoader.js';
import { REBANK_DONE_AT_S, REBANK_UNDO_BELOW_S, rebankDecision } from './rebank.js';
import { createRungMarks } from './rungMarks.js';
import { KEEP_BEHIND_S, createSegmentBank } from './segmentBank.js';
import { createStepGate } from './stepGate.js';
import { addSample, fragmentKbps, lowerMiddle, networkGeneration } from './throughput.js';

export { RECONNECT_MS };
// How often the rate of the fragment in flight is read for the floor hold, in ms.
export const HOLD_CHECK_MS = 2000;
// A fragment in flight counts for the release only once it has been receiving this long, in ms, counted from
// its first byte: a cold first byte can take seconds, and a fragment that stays silent is ended as trouble.
export const IN_FLIGHT_MIN_AGE_MS = 4000;
// Fragments in a row that needed a retry before the rates for the rungs above 480p are forgotten.
export const RETRIED_CLEARS = 4;
// How many of the newest fragment rates the release reads.
const RELEASE_SAMPLES = 3;
// hls.js 1.7.4's `ErrorActionFlags.ResetMediaSource`: its error controller has already run `recoverMediaError` for
// an error carrying it (a const enum there, so no run-time export).
const RESET_MEDIA_SOURCE = 16;
// The bank is armed with a stretch this near (the 20 minutes of the rule plus hls.js's 480 s and the back buffer)
// and turned on with one this near, in seconds; the front's rung is chosen once a minute, in ms.
export const BANK_ARM_WITHIN_S = 1800;
export const BANK_ON_WITHIN_S = 1200;
export const BANK_CHOICE_EVERY_MS = 60000;
// While an episode runs the engine steps the bank this often, in ms, on the last `bankInput`: the front has no
// timer of its own, so this tick hands it the film ahead and the need (`update`) as the film plays.
export const BANK_TICK_MS = 1000;

// kbit/s of one loaded fragment, timed from `loading.start` so the wait for the first byte counts; null when
// there is nothing to measure.
function loadedKbps(stats) {
  const start = stats?.loading?.start;
  const ms = stats?.loading?.end - start;
  return stats?.loaded > 0 && Number.isFinite(start) && ms > 0
    ? Math.round((stats.loaded * 8) / ms)
    : null;
}

const retries = (max) => ({
  maxNumRetry: max,
  retryDelayMs: 1000,
  maxRetryDelayMs: 8000,
  backoff: 'exponential',
});

/**
 * hls.js's `shouldRetry` hook: its own answer (`retry`), or a request that got no HTTP answer at all (status 0)
 * while retries remain, whatever `navigator.onLine` says: it is retried with backoff like a 5xx instead of
 * costing a rung. Timeouts and HTTP answers keep hls.js's decision.
 */
export function retryNoAnswer(retryConfig, retryCount, isTimeout, loaderResponse, retry) {
  return Boolean(
    retry || (!isTimeout && loaderResponse?.code === 0 && retryCount < retryConfig.maxNumRetry),
  );
}

// A 503 with Retry-After: 1 (the encoder catching up) is an error response, so errorRetry retries it.
const policy = (ttfbMs, loadMs, maxRetries) => ({
  default: {
    maxTimeToFirstByteMs: ttfbMs,
    maxLoadTimeMs: loadMs,
    timeoutRetry: retries(maxRetries),
    errorRetry: { ...retries(maxRetries), shouldRetry: retryNoAnswer },
  },
});

// The least film kept ahead on any level, in seconds (hls.js's `maxBufferLength`).
export const MIN_BUFFER_AHEAD_S = 120;
// The most film kept ahead on any level, in seconds (hls.js's `maxMaxBufferLength`).
export const MAX_BUFFER_AHEAD_S = 480;
// The byte budget between the two, in bytes (hls.js's `maxBufferSize`).
export const BUFFER_BUDGET_BYTES = 100 * 1000 * 1000;

/**
 * hls.js settings. The forward buffer is as deep as the byte budget allows at the level's peak bitrate, never
 * under MIN_BUFFER_AHEAD_S and never over MAX_BUFFER_AHEAD_S: eight minutes on the levels whose eight minutes
 * fit in 100 MB (240p, 360p, 480p), about 256 s at 720p, about 130 s at a 1080p transcode, and 120 s (the
 * original) on heavy originals. A deep buffer on the low levels banks film while the link is good. If the
 * browser's buffer fills, hls.js lowers its own ceiling and carries on.
 */
export function hlsConfig(startSeconds) {
  return {
    maxBufferLength: MIN_BUFFER_AHEAD_S,
    maxMaxBufferLength: MAX_BUFFER_AHEAD_S,
    maxBufferSize: BUFFER_BUDGET_BYTES,
    backBufferLength: 30,
    // Standard MediaSource only: iOS (ManagedMediaSource) plays natively instead.
    preferManagedMediaSource: false,
    // Only a manual level (`loadLevel`, set while the film bank is on) is kept through an error; in Auto, which
    // is every other case, hls.js's own rule leaves it as it was.
    preserveManualLevelOnError: true,
    startPosition: startSeconds > 0 ? startSeconds : -1,
    // A rung penalised by a fatal-looking blip may be chosen again after the minute the step gate asks for.
    errorPenaltyExpireMs: 60000,
    // A segment may take 120 s (hls.js's default); the no-progress loader ends one that has gone silent sooner.
    fragLoadPolicy: policy(10000, 120000, 8),
    playlistLoadPolicy: policy(10000, 20000, 6),
    manifestLoadPolicy: policy(10000, 20000, 4),
  };
}

/**
 * Starts `session` on `video`. `handlers`: onLevel(rung, { initial }), onThroughput(kbps),
 * onReconnecting(boolean), onSessionLost(), onFatal(), and the optional onStopped() (a 410: Admin stopped this
 * playback, so nothing is recovered), onNetworkError({ timeout }),
 * onFetching(rung) (the rung hls.js has started to fetch, ahead of the one being played),
 * onRebank('started' | 'done' | 'undone') (once for each, at the one place it happens; 'undone' only for a re-bank
 * that was under way and only while the record is still open: one under way when the playback ends is neither
 * done nor undone, so started may exceed done + undone by one), onMarksForgotten(n) (n hls.js fragment-error marks forgotten in one sweep),
 * and the look-ahead's record (train-lookahead-plan.md, 5b; no position is ever in it): onLookaheadHeld() (each
 * `bankInput` that is not null, with whether its stretch has begun and a function describing the stretch as
 * { needS, aheadS, rung } in whole seconds, `rung` the bank's rung id or null; the record calls it once per
 * stretch, at the first input saying it has begun after one that did not),
 * onBankRungChange() (the bank chose another rung while on) and onBankBypass() (a media request went to the
 * server through hls.js's own loader while the bank was on or turning off, or while a bank front still had a
 * fetch in flight; the release and the bank's own fetcher are never counted). In Auto, on a ladder
 * with a 240p bottom and a 360p level, a deep buffer holds Auto at 360p or above while it is fetching there
 * and has had no fragment trouble; the cap never climbs above the level being fetched by more than one.
 * `fetch` is the film bank's (default: the browser's). Returns { destroy, bankInput } and the bank's own
 * `bankOn`, `bankWrongRung` and `bankRelease`.
 */
export function startHlsJs({
  Hls,
  video,
  session,
  auto,
  startSeconds,
  handlers,
  now = () => Date.now(),
  clock = () => performance.now(), // the clock hls.js stamps `loading.start` with
  reconnectMs = RECONNECT_MS,
  fetch: fetchFn,
}) {
  const config = hlsConfig(startSeconds);
  // The film bank's loaders wrap today's, and ask `decider` about every request: with no bank episode it passes them
  // all, so nothing changes until a stretch is near. A pinned rung never has a bank: today's loader alone.
  const waiting = new Set(); // the requests hls.js waits on the bank for
  const finishing = new Set(); // fronts of closed episodes still finishing a fetch; a destroy stops them
  let episode = null; // while the bank is armed, on or turning off: { front, requests, rungIds, candidates }
  const decider = {
    armed: () => episode?.requests.armed() ?? false,
    fragmentRequest(request, reply) {
      if (episode === null) return { action: 'pass' };
      // The requests hls.js is waiting on, until each is answered, failed, timed out or aborted.
      const token = {};
      let over = false;
      const ended = () => {
        over = true;
        waiting.delete(token);
      };
      const decision = episode.requests.fragmentRequest(request, {
        success: (...args) => {
          ended();
          reply.success(...args);
        },
        error: (...args) => {
          ended();
          reply.error(...args);
        },
        timeout: () => {
          ended();
          reply.timeout();
        },
      });
      if (decision.action !== 'wait') return decision;
      if (!over) waiting.add(token);
      return {
        ...decision,
        cancel: () => {
          ended();
          decision.cancel?.();
        },
      };
    },
    fragmentLoaded: (loaded) => episode?.requests.fragmentLoaded(loaded),
    playlistText: (context) => episode?.requests.playlistText(context) ?? null,
  };
  const innerF = createNoProgressLoader(Hls.DefaultConfig.loader);
  const loaders = auto
    ? createBankLoaders({
        InnerF: innerF,
        InnerP: Hls.DefaultConfig.loader,
        bank: decider,
        now: clock,
        onPass: () => {
          // A release is the bank handing hls.js back its own loading, not a bypass.
          if (releasing) {
            releasing = false;
            return;
          }
          if (phase === 'on' || phase === 'turningOff' || frontBusy()) handlers.onBankBypass?.();
        },
      })
    : null;
  const hls = new Hls({
    ...config,
    // `fLoader` is set here, not in `hlsConfig`, because only here is `Hls` at hand.
    fLoader: loaders ? loaders.fLoader : innerF,
    ...(loaders ? { pLoader: loaders.pLoader } : {}),
  });
  const gate = createStepGate({ now });
  const marks = createRungMarks({ now });
  const { Events, ErrorTypes, ErrorDetails } = Hls;
  let top = 0;
  let applied = null; // the cap on hls.js: the lower of the step gate's level and the champagne ceiling
  let gateCap = null; // the step gate's own level
  let ceiling = 0; // the champagne ceiling last worked out
  let initial = true;
  let lost = false;
  // Admin stopped this playback (410), or the session is gone (404): once, whichever of hls.js's requests or the
  // film bank's front hears it first.
  const endStopped = () => {
    if (lost) return;
    lost = true;
    handlers.onStopped?.();
  };
  const endLost = () => {
    if (lost) return;
    lost = true;
    handlers.onSessionLost();
  };
  let reconnecting = false;
  let timer = null; // the one pending restart, if any
  const hold = createFloorHold();
  let holdLevel = null; // the 360p level of a ladder with a 240p bottom (the only kind that is held)
  let floorSet = 0; // what `minAutoBitrate` was last set to
  let holdFloor = 0; // the floor hold's share of it (bit/s)
  let rebankFloor = 0; // the re-bank's share: the new rung's bitrate while a re-bank is under way
  let flushed = false; // BUFFER_FLUSHED has come for the re-bank's own flush
  let flushFrom = null; // where that flush starts: hls.js's own flushes (back buffer) are not it
  let banked = []; // the fragments in the buffer, { start, end, level }, from FRAG_BUFFERED
  let loadingFrag = null; // the newest media fragment asked for and not yet buffered (null: none in flight)
  let lastTroubleAt = null; // when fragment trouble or a stall was last seen (ms on `now`)
  let lastStepDownAt = null;
  let lastRebankAt = null;
  let lastUndoAt = null;
  let fetched = null; // the level hls.js is fetching (LEVEL_SWITCHING), null until the first
  let troubled = false; // a fragment timed out or got no answer, and none has loaded since
  let samples = []; // the newest fragment rates (kbit/s) for the hold, under `samplesNetwork`
  let headroom = []; // the newest first-byte fragment rates (kbit/s), retried fragments left out
  let retriedRun = 0; // fragments in a row that needed a retry
  let samplesNetwork = networkGeneration();
  let inFlight = null; // the fragment hls.js is loading, whose rate so far the release reads
  let holdTimer = null; // the one check of the in-flight rate, running while a fragment loads
  let bank = null; // while the film bank is on: { levelAt(seconds), frontLevel() } (hls.js level indices)
  let destroyed = false;
  const store = auto ? createSegmentBank() : null; // the film bank's segments and playlists
  let phase = 'idle'; // the bank's: idle, armed, on, turningOff
  let releasing = false; // the decider is releasing: the pass about to follow is not counted as a bypass
  // A bank front (the episode's, or one of a closed episode still finishing) has a fetch in flight.
  const frontBusy = () =>
    (episode !== null &&
      (episode.front.fetching() !== null || episode.front.boundaryFetching() !== null)) ||
    [...finishing].some((front) => !front.isFinished());
  let lookahead = null; // the last bankInput
  let lastChoiceAt = null; // when the front's rung was last chosen (clock ms), null: not yet for this stretch
  // Where loading resumes: the playhead as last seen with media loaded (-1: from the start), else the start asked
  // for. hls.js reloads from `config.startPosition` when it reattaches the media on its own (after a reset), and the
  // playhead reads 0 by then, so that setting follows the playhead too and never takes the film back to where it began.
  let resumeAt = config.startPosition;
  const rememberPlayhead = () => {
    if (video.readyState === 0) return;
    resumeAt = video.currentTime > 0 ? video.currentTime : -1;
    hls.config.startPosition = resumeAt;
  };

  // The one writer of `minAutoBitrate`: the larger of the hold's floor and the re-bank's.
  const writeFloor = () => {
    const floor = Math.max(holdFloor, rebankFloor);
    if (floor === floorSet) return;
    floorSet = floor;
    hls.config.minAutoBitrate = floor;
  };

  // Ends a re-bank: the floor comes off first, then the ten minutes of cool-down start. Events that say the link
  // or the playhead is not to be trusted (a seek, a restart, a stall, trouble, a new network, destroy) always
  // start it; the others use `undoIfRebanking`.
  const undoRebank = () => {
    // Only a re-bank under way is undone: most callers run whether or not there is one, and count once.
    if (rebankFloor > 0) handlers.onRebank?.('undone');
    rebankFloor = 0;
    writeFloor();
    lastUndoAt = now();
  };
  const undoIfRebanking = () => {
    if (rebankFloor > 0) undoRebank();
  };

  // The samples, emptied first when the network has changed since they were taken.
  const currentSamples = () => {
    if (samplesNetwork !== networkGeneration()) {
      undoRebank();
      samples = [];
      headroom = [];
      retriedRun = 0;
      ceiling = champagneBase(hls.levels); // nothing measured on this link has earned a rung above 480p
      samplesNetwork = networkGeneration();
      hold.reset(); // the link it was armed on is gone
    }
    return samples;
  };

  // The rate of the fragment in flight since its first byte, null when none is loading, when no byte has come
  // yet (noProgressLoader and hls.js's first-byte limit end a silent fragment as trouble) or when the transfer
  // is younger than IN_FLIGHT_MIN_AGE_MS.
  const inFlightKbps = () => {
    const stats = inFlight?.stats;
    const first = stats?.loading?.first;
    if (!stats || stats.aborted || stats.loading.end > 0 || !(first > 0) || !(stats.loaded > 0)) {
      return null;
    }
    const ms = clock() - first;
    return ms >= IN_FLIGHT_MIN_AGE_MS ? (stats.loaded * 8) / ms : null;
  };

  const stopHoldTimer = () => {
    clearInterval(holdTimer);
    holdTimer = null;
    inFlight = null;
  };

  // Raises hls.js's minimum bitrate to the 360p level while the buffer is deep, the cap allows 360p, hls.js is
  // already fetching at 360p or above and no fragment is in trouble.
  const reevaluateHold = () => {
    if (bank || holdLevel === null) return;
    const bitrate = hls.levels[holdLevel]?.bitrate ?? 0; // bit/s, the variant's peak
    const rateKbps = bitrate / 1000;
    const recent = currentSamples();
    const flying = inFlightKbps();
    const armed = hold.update({
      aheadS: bankedAhead(video),
      armKbps: lowerMiddle(recent),
      releaseKbps: Math.min(...recent.slice(-RELEASE_SAMPLES), flying ?? Infinity),
      rateKbps,
    });
    const engaged =
      armed &&
      !troubled &&
      applied !== null &&
      applied >= holdLevel &&
      fetched !== null &&
      fetched >= holdLevel;
    const wasEngaged = holdFloor > 0;
    holdFloor = engaged ? bitrate : 0;
    if (wasEngaged && !engaged) undoIfRebanking(); // the hold let go: the bank is no longer deep and safe
    writeFloor();
  };

  // Banked fragments still in the buffer: an entry stays while its middle is inside a buffered range.
  const pruneBanked = () => {
    const { buffered } = video;
    banked = banked.filter((f) => {
      const mid = (f.start + f.end) / 2;
      for (let i = 0; i < buffered.length; i += 1) {
        if (buffered.start(i) <= mid && mid <= buffered.end(i)) return true;
      }
      return false;
    });
  };

  // Re-bank, once per tick: undo it when the bank runs low, call it done when the bank is back, and otherwise ask
  // `rebankDecision`. The floor is raised BEFORE the flush, so hls.js refetches at the new rung and not the old.
  // Auto only; a pinned quality never re-banks.
  const rebankStep = () => {
    if (!auto || bank) return;
    const aheadS = bankedAhead(video);
    if (rebankFloor > 0) {
      if (aheadS < REBANK_UNDO_BELOW_S) {
        undoRebank();
      } else if (flushed && aheadS >= REBANK_DONE_AT_S) {
        handlers.onRebank?.('done');
        rebankFloor = 0; // no cool-down: the next rung can come after the quiet time
        lastRebankAt = now();
        writeFloor();
      }
      return;
    }
    pruneBanked();
    const decision = rebankDecision({
      levels: hls.levels,
      applied,
      fetched,
      banked,
      playhead: video.currentTime,
      aheadS,
      headroom,
      inFlight: loadingFrag !== null,
      troubled,
      now: now(),
      lastTroubleAt,
      lastStepDownAt,
      lastRebankAt,
      lastUndoAt,
    });
    if (!decision) return;
    handlers.onRebank?.('started');
    rebankFloor = hls.levels[decision.level].bitrate;
    flushed = false;
    flushFrom = decision.from;
    writeFloor();
    hls.trigger(Events.BUFFER_FLUSHING, {
      startOffset: decision.from,
      endOffset: Infinity,
      type: null,
    });
  };

  // The one place the cap is worked out: the step gate's level, never above the champagne ceiling. A gate level
  // above the ceiling is pulled down to it, so a ceiling that comes back climbs a rung at a time.
  const applyCap = () => {
    if (bank) return; // the bank's manual level is in charge
    if (auto && gateCap !== null) {
      currentSamples();
      const raised = champagneCeiling({
        levels: hls.levels,
        applied: applied ?? 0,
        count: headroom.length,
        aheadS: bankedAhead(video),
        kbps: lowerMiddle(headroom),
        troubled,
        bank: {
          budgetBytes: BUFFER_BUDGET_BYTES,
          minS: MIN_BUFFER_AHEAD_S,
          maxS: MAX_BUFFER_AHEAD_S,
        },
      });
      // While the last fragments needed a retry (until a clean one loads) the ceiling never rises.
      ceiling = retriedRun > 0 ? Math.min(raised, ceiling) : raised;
      if (gateCap > ceiling) gateCap = gate.switched(ceiling);
      const level = Math.min(gateCap, ceiling);
      if (level !== applied) {
        applied = level;
        hls.autoLevelCapping = level;
      }
    }
    reevaluateHold();
  };

  // The step gate moved to `level` (or is asked again): the cap follows.
  const cap = (level) => {
    gateCap = level;
    applyCap();
  };

  // The hole hls.js plays across when it picks its next fragment (`getFwdBufferInfo`): `maxBufferHole`, none
  // while paused or just after a backward seek (the playhead is behind where the last time update left it).
  let lastTime = 0;
  let lastHole = 0; // the hole used for the last level written
  let pending = null; // the fragment loaded and not yet appended, { sn, level, start, end }
  const holeS = () =>
    video.paused || lastTime > video.currentTime ? 0 : (hls.config.maxBufferHole ?? 0);

  // The first film second hls.js has not got from `t` on, by its own rule (`buffer-helper.ts`): the end of the
  // buffered range holding `t`, ranges less than a hole apart (or touching) counting as one; `t` itself when
  // none holds it (a range starts within a hole of `t` counts as holding it, as in hls.js's `bufferedInfo`).
  // `extra` is a range about to be appended (a loaded fragment), added to the buffered ones.
  const bufferEnd = (t, extra = null) => {
    const { buffered } = video;
    const ranges = [];
    for (let i = 0; i < buffered.length; i += 1) {
      ranges.push({ start: buffered.start(i), end: buffered.end(i) });
    }
    if (extra) ranges.push(extra);
    ranges.sort((x, y) => x.start - y.start);
    const hole = holeS();
    const merged = [];
    for (const r of ranges) {
      const last = merged.at(-1);
      if (last && (r.start <= last.end || r.start - last.end < hole))
        last.end = Math.max(last.end, r.end);
      else merged.push({ ...r });
    }
    return merged.findLast((r) => r.start <= t + hole && t < r.end)?.end ?? t;
  };

  // hls.js's next fragment is the one at `position`: it is loaded at the rung the bank holds there, or, past
  // what is held, at the rung the bank's front fetches. Written in every case, never only on a change.
  const holdBankLevelAt = (position) => {
    lastHole = holeS();
    hls.loadLevel = bank.levelAt(position) ?? bank.frontLevel();
  };
  // The same for the film after the buffered range at the playhead.
  // A fragment loaded but not appended yet counts as buffered, so a flush that comes first cannot hide it.
  const holdBankLevelAtPlayhead = () => holdBankLevelAt(bufferEnd(video.currentTime, pending));
  // The pending fragment itself (a main one with its sequence number and level), not another track's.
  const isPending = (frag) =>
    pending !== null &&
    Boolean(frag) &&
    (!frag.type || frag.type === 'main') &&
    pending.sn === frag.sn &&
    pending.level === frag.level;
  // hls.js has thrown away the fragment loaded but not appended (no event says so): the level follows the
  // buffer as it is, not the film after the dropped fragment.
  const dropPending = () => {
    pending = null;
    if (bank) holdBankLevelAtPlayhead();
  };
  // The same after hls.js reset the media itself (`recoverMediaError`, run by its error controller before this
  // engine hears of the error): the playhead reads 0 and nothing is buffered, so the level is for hls.js's reload
  // position as best known. When loading restarted at once (`loadingEnabled`) that is `hls.startPosition`, the
  // time `startLoad(time)` was given; otherwise hls.js reloads later, on MEDIA_ATTACHED, from its configured start
  // position when there is one, else from where it last saw the playhead (the last time update here). A miss
  // costs one request: the bank answers a request at the wrong rung with a bank-rung 503 after `bankWrongRung`.
  const dropPendingAfterReset = () => {
    pending = null;
    if (!bank) return;
    const configured = hls.config.startPosition;
    holdBankLevelAt(
      hls.loadingEnabled ? hls.startPosition : configured > 0 ? configured : lastTime,
    );
  };
  // The hole changes with pause, play and the end of a backward seek, with no event of hls.js's: the level follows.
  const followHole = () => {
    if (bank && holeS() !== lastHole) holdBankLevelAtPlayhead();
  };

  const stopReconnecting = () => {
    clearTimeout(timer);
    timer = null;
    if (!reconnecting) return;
    reconnecting = false;
    handlers.onReconnecting(false);
  };

  // At the playhead once the video has metadata; before that it reads 0, so a resumed film restarts where it
  // was last seen, or asked to start (-1 loads from the beginning).
  const restart = () => {
    clearTimeout(timer);
    timer = null;
    stopHoldTimer(); // the fragment in flight is aborted by the reload
    loadingFrag = null;
    dropPending(); // `stopLoad` throws away a fragment loaded but not appended
    undoRebank();
    // What was earned before the break is earned again: the rates are forgotten and the cap falls at once.
    ceiling = champagneBase(hls.levels ?? []);
    headroom = [];
    retriedRun = 0;
    applyCap();
    if (hls.levels?.length) {
      hls.startLoad(video.readyState > 0 ? video.currentTime : resumeAt);
    } else {
      hls.loadSource(session.masterUrl);
    }
  };

  // One restart per fatal network error, `reconnectMs` later, replacing one already pending. Both `startLoad`
  // and `loadSource` abort the request the one before made, so nothing is scheduled again until the next
  // fatal error: hls.js's own backoff does the retrying in between.
  const beginReconnecting = () => {
    clearTimeout(timer);
    if (!reconnecting) {
      reconnecting = true;
      handlers.onReconnecting(true);
    }
    timer = setTimeout(restart, reconnectMs);
  };

  // The connection is back: a pending restart need not wait out its 5 s.
  const onOnline = () => {
    if (timer !== null) restart();
  };
  window.addEventListener('online', onOnline);

  const onWaiting = () => {
    gate.trouble();
    marks.trouble();
    lastTroubleAt = now();
    undoRebank();
    reevaluateHold();
  };
  // The bank is full, so hls.js is mostly idle and sends no fragment events: the re-bank is read on timeupdate.
  const onTimeUpdate = () => {
    lastTime = video.currentTime;
    rememberPlayhead();
    followHole();
    applyCap();
    rebankStep();
    bankStep();
  };
  const onSeeking = () => {
    rememberPlayhead();
    undoRebank();
    bankSeek();
    if (bank) holdBankLevelAtPlayhead();
    else applyCap();
  };
  video.addEventListener('waiting', onWaiting);
  video.addEventListener('timeupdate', onTimeUpdate);
  video.addEventListener('seeking', onSeeking);
  video.addEventListener('play', followHole);
  video.addEventListener('pause', followHole);

  hls.on(Events.MANIFEST_PARSED, (_event, data) => {
    top = data.levels.length - 1;
    const index = data.levels.findIndex((l) => rungForLevel(session.renditions, l) === '360p');
    const qualifies =
      auto && index > 0 && rungForLevel(session.renditions, data.levels[0]) === '240p';
    holdLevel = qualifies ? index : null;
    if (auto) {
      // The playlist lists the session's start rung first, and a remembered fast speed could start at 720p or
      // above with an empty bank: `applyCap` clamps the start to the ceiling, which starts at the 480p level.
      ceiling = champagneBase(data.levels);
      cap(gate.start(data.firstLevel));
    }
  });

  hls.on(Events.LEVEL_SWITCHED, (_event, data) => {
    const rung = rungForLevel(session.renditions, hls.levels[data.level]);
    if (!rung) return;
    handlers.onLevel(rung, { initial });
    initial = false;
  });

  // The level hls.js now fetches: the gate follows it, so a step down lowers the cap at once.
  hls.on(Events.LEVEL_SWITCHING, (_event, data) => {
    // hls.js has come up from a lower level to the cap: the healthy minute starts now.
    const arrived = auto && fetched !== null && fetched < data.level && data.level === applied;
    if (fetched !== null && data.level < fetched) lastStepDownAt = now();
    fetched = data.level;
    const rung = rungForLevel(session.renditions, hls.levels[data.level]);
    if (!rung) return;
    handlers.onFetching?.(rung);
    if (arrived) gate.arrived();
    if (auto) cap(gate.switched(data.level));
    reevaluateHold();
  });

  // `frag` is no longer in flight. A newer fragment that has started since stays: it is told apart by object, or
  // by sequence number and level.
  const endLoading = (frag) => {
    const same =
      frag &&
      loadingFrag &&
      (frag === loadingFrag || (frag.sn === loadingFrag.sn && frag.level === loadingFrag.level));
    if (same) loadingFrag = null;
  };

  // A fragment starts to load (in Auto): the hold and the ceiling are re-read every HOLD_CHECK_MS until it ends, the
  // hold with the rate of the fragment so far. An init segment is a
  // few bytes behind a round trip, so it says nothing about the link.
  hls.on(Events.FRAG_LOADING, (_event, data) => {
    if (!auto || data.frag?.sn === 'initSegment') return;
    loadingFrag = data.frag;
    inFlight = data.frag;
    holdTimer ??= setInterval(applyCap, HOLD_CHECK_MS);
  });

  // hls.js gave up on the fragment for a faster level: nothing is in flight any more.
  hls.on(Events.FRAG_LOAD_EMERGENCY_ABORTED, (_event, data) => {
    endLoading(data?.frag);
    stopHoldTimer();
  });

  // A fragment is in the buffer: it joins the list the re-bank reads. `loadingFrag` is cleared here, not on
  // FRAG_LOADED, because a fragment loaded but not yet appended would land after a flush as an island at the old
  // rung. hls.js's own FRAG_BUFFERED handler runs first and may start the next fragment (FRAG_LOADING) before this
  // one runs, so only the fragment named is cleared (`endLoading`). If an event is missed and `loadingFrag` sticks,
  // the only cost is that no re-bank starts (safe); the next fragment's FRAG_LOADING and FRAG_BUFFERED put it right.
  hls.on(Events.FRAG_BUFFERED, (_event, data) => {
    endLoading(data.frag);
    const frag = data.frag;
    if (isPending(frag)) pending = null;
    bankStep(); // nothing is in flight now: the bank may turn on
    if (!frag || frag.sn === 'initSegment' || (frag.type && frag.type !== 'main')) return;
    const entry = { start: frag.start, end: frag.start + frag.duration, level: frag.level };
    banked = [...banked.filter((f) => f.start !== entry.start), entry];
    rebankStep();
  });

  // hls.js sends { type, start, end } of the flush that finished; its error path sends 0 and 0.
  hls.on(Events.BUFFER_FLUSHED, (_event, data) => {
    pruneBanked();
    // A flush that reaches the film ahead moves the buffered end the next fragment follows.
    if (bank && data?.end > video.currentTime) holdBankLevelAtPlayhead();
    if (rebankFloor > 0 && data?.end === Infinity && Math.abs(data.start - flushFrom) < 0.5) {
      flushed = true;
    }
  });

  hls.on(Events.FRAG_LOADED, (_event, data) => {
    const frag = data.frag;
    if (
      bank &&
      frag &&
      frag.sn !== 'initSegment' &&
      (!frag.type || frag.type === 'main') &&
      Number.isFinite(frag.start + frag.duration)
    ) {
      // Before hls.js chooses its next fragment: it is not appended yet, so the film after it is read from what
      // is buffered around its end (a range that starts there joins it when it lands).
      pending = {
        sn: frag.sn,
        level: frag.level,
        start: frag.start,
        end: frag.start + frag.duration,
      };
      holdBankLevelAt(bufferEnd(frag.start, pending));
    }
    stopReconnecting();
    troubled = false;
    stopHoldTimer();
    // A fragment the bank answered says nothing about the link (decision 6): the front's own fetches report.
    const answered = data.frag?.stats?.bankAnswered === true;
    if (!answered) episode?.front.serverAnswered(); // a successful server answer ends the front's pause
    const kbps = answered ? null : fragmentKbps(data.frag?.stats);
    if (kbps) handlers.onThroughput(kbps);
    if (data.frag?.sn !== 'initSegment' && !answered) {
      currentSamples();
      samples = addSample(samples, loadedKbps(data.frag?.stats));
      if (data.frag?.stats?.retry > 0) {
        // A fragment that needed a retry says nothing good about the link: it is no sample, the ceiling may not
        // rise on it, and a run of RETRIED_CLEARS of them forgets the rates, so a link that is degrading
        // fragment by fragment loses its rungs above 480p. Fewer would punish an encoder that is still
        // catching up (503s) on the first fragments of a new rung.
        retriedRun += 1;
        if (retriedRun >= RETRIED_CLEARS) headroom = [];
      } else {
        retriedRun = 0;
        headroom = addSample(headroom, kbps);
      }
    }
    if (bank) return;
    if (auto) {
      const forgotten = marks.sweep(hls.levels, fetched).length;
      if (forgotten > 0) handlers.onMarksForgotten?.(forgotten);
    }
    applyCap();
    // The cap rises only while hls.js is fetching at it, so it is never more than a level above the fetch, and
    // the step gate never runs ahead of the ceiling.
    if (auto && (fetched === null || fetched >= applied) && gateCap < ceiling) cap(gate.tick(top));
  });

  hls.on(Events.ERROR, (_event, data) => {
    if (data.type === ErrorTypes.NETWORK_ERROR && !data.response?.code) {
      // A load that got no HTTP answer and failed or ran out of time, for the health record. A load aborted on
      // purpose, and a playlist that arrived but could not be read, are not counted.
      const details = String(data.details ?? '');
      if (details.endsWith('LoadTimeOut')) handlers.onNetworkError?.({ timeout: true });
      else if (details.endsWith('LoadError')) handlers.onNetworkError?.({ timeout: false });
    }
    if (
      data.details === ErrorDetails.FRAG_LOAD_ERROR &&
      data.response?.code === 503 &&
      data.response?.text === BANK_RUNG_TEXT
    ) {
      // The bank's own answer, not the server's: hls.js has counted it against the rung's `fragmentError`, which
      // sets every later retry's delay and budget, so the count is taken back.
      const level = hls.levels?.[data.frag?.level];
      if (level?.fragmentError > 0) level.fragmentError -= 1;
    }
    // Fragment trouble: a timeout, or an error with no HTTP answer (a 503 while the encoder catches up is not).
    if (
      data.details === ErrorDetails.FRAG_LOAD_TIMEOUT ||
      (data.details === ErrorDetails.FRAG_LOAD_ERROR && !data.response?.code)
    ) {
      gate.trouble();
      marks.trouble();
      lastTroubleAt = now();
      undoRebank();
      if (auto && fetched !== null) gateCap = gate.switched(fetched);
      troubled = true;
      samples = []; // what was measured before the trouble says nothing about the link now
      headroom = [];
      retriedRun = 0;
      hold.reset();
      stopHoldTimer();
      applyCap();
    }
    if (isPending(data.frag)) pending = null; // its append failed: hls.js loads it again
    if (String(data.details ?? '').startsWith('frag')) endLoading(data.frag); // a failed fragment is not loading
    if (data.response?.code === 410) {
      // Admin stopped this playback on purpose: no new session, no retry (owner 2026-11-15).
      endStopped();
      return;
    }
    if (data.response?.code === 404) {
      // The session expired (or was replaced): the caller makes a new one at the current position.
      endLost();
      return;
    }
    if (
      !data.fatal &&
      data.type === ErrorTypes.MEDIA_ERROR &&
      data.details === ErrorDetails.MEDIA_SOURCE_REQUIRES_RESET
    ) {
      // A non-fatal reset is recovered by hls.js itself, before this handler runs, and the recovery drops the
      // fragment loaded but not appended. (A fatal one that carries the reset flag is recovered the same way; one that does not is recovered below.)
      dropPendingAfterReset();
    }
    bankStep(); // a failed fragment is not in flight any more
    if (!data.fatal) return;
    if (data.type === ErrorTypes.NETWORK_ERROR) {
      dropPending(); // hls.js stops loading here: the fragment loaded but not appended is gone
      if (phase === 'on') episode.front.pause(); // decision 8: the clock runs from the first pause only
      beginReconnecting();
    } else if (data.type === ErrorTypes.MEDIA_ERROR) {
      // hls.js's error controller runs before this handler: for a reset it has recovered already, and for every
      // fatal error it has then stopped loading (`started` false), so a recovery of ours would reset the media a
      // second time, from a playhead that reads 0, and `recoverMediaError` would not start loading again.
      pending = null; // the recovery detaches the media: a fragment loaded but not appended is thrown away
      const resumeFrom = video.readyState > 0 ? video.currentTime : resumeAt;
      if (((data.errorAction?.flags ?? 0) & RESET_MEDIA_SOURCE) !== 0) {
        dropPendingAfterReset();
      } else {
        // The recovery loads from the playhead: at the rung the bank holds there.
        if (bank) holdBankLevelAt(video.currentTime);
        hls.recoverMediaError();
      }
      if (!hls.loadingEnabled) hls.startLoad(resumeFrom);
    } else handlers.onFatal();
  });

  // ---- The film bank's wiring (outside-bank-plan.md, decisions 2, 6, 7 and 8) ----
  // One episode per stretch: armed (the front fetches the playlists, the loaders keep copies of what hls.js loads),
  // on, turning off (held film is still answered), then none. The store outlives it, as do its playlists.
  const { renditions } = session;
  const rungOfLevelIndex = (level) => rungForLevel(renditions, hls.levels[level]);
  // The rung hls.js level `level` is, as far as the bank keeps it (480p and below), else null.
  const bankRungOfLevel = (level) => {
    const id = rungOfLevelIndex(level);
    return renditions.find((r) => r.id === id)?.height <= FRONT_MAX_HEIGHT ? id : null;
  };
  const bankLevelOfRung = (id) => hls.levels.findIndex((l) => rungForLevel(renditions, l) === id);
  const bankRungs = () =>
    hls.levels.flatMap((level) => {
      const rendition = renditions.find((r) => r.id === rungForLevel(renditions, level));
      return rendition?.height <= FRONT_MAX_HEIGHT
        ? [
            {
              id: rendition.id,
              height: rendition.height,
              kbps: level.bitrate / 1000,
              playlistUrl: level.uri,
            },
          ]
        : [];
    });
  const filmAheadNow = () => Math.max(bankedAhead(video), store.filmAheadHeld(video.currentTime));
  const needOf = (input) => (input.need > 0 ? input.need : 0);
  const within = (input, seconds) => input !== null && input.timeToStretch <= seconds;

  // The bank's rung id: the front's while an episode runs, else the rung hls.js is fetching; null when unknown.
  const bankRungNow = () => {
    if (episode !== null)
      return episode.front.window().rung ?? episode.front.position().rung ?? null;
    return fetched === null ? null : (rungOfLevelIndex(fetched) ?? null);
  };

  // The look-ahead's record of one input: handed to the record with whether the stretch has begun, which keeps
  // the begun state across engines (a rebuilt engine is handed the same input again) and takes a description of
  // the stretch only when it is reached. Nothing positional is described.
  const recordInput = (input) => {
    if (input === null) return;
    handlers.onLookaheadHeld?.(input.stretchBegun === true, () => ({
      needS: Math.round(needOf(input)),
      aheadS: Math.round(filmAheadNow()),
      rung: bankRungNow(),
    }));
  };

  // The rung the bank last fetched at, as the level hls.js falls back to on a release.
  const fetchedBankLevel = () => {
    const { front } = episode;
    return bankLevelOfRung(front.window().rung ?? front.position().rung);
  };

  // Ends the episode: the front finishes (its fetch in flight answers whoever waits on it, nothing new starts),
  // hls.js is released from the manual level (a no-op if never on) and what is held is dropped; playlists stay.
  const closeEpisode = () => {
    const done = episode;
    const level = fetchedBankLevel();
    episode = null;
    phase = 'idle';
    pendingSeek = false;
    waiting.clear();
    clearInterval(done.tick);
    done.seal(); // what its fetch in flight lands is for its waiters only, never the store
    done.front.finish();
    if (!done.front.isFinished()) finishing.add(done.front);
    done.requests.setMode('off');
    engine.bankRelease(level);
    store.clear();
  };

  const startEpisode = () => {
    const rungs = bankRungs();
    if (rungs.length === 0) return;
    const policy = config.fragLoadPolicy.default;
    // The episode's own view of the store: once sealed (the episode closed and the store cleared) a finishing
    // front's puts are kept for its subscribers (`getSegment`) and dropped from the store.
    let sealed = false;
    const shadow = new Map();
    const view = {
      ...store,
      putSegment(segment) {
        if (sealed) shadow.set(segment.seq, segment);
        else store.putSegment(segment);
      },
      putBoundary(...args) {
        if (!sealed) store.putBoundary(...args);
      },
      getSegment(seq) {
        const own = shadow.get(seq);
        if (own === undefined) return store.getSegment(seq);
        return { ...own, bytes: own.bytes.slice(0), timings: { ...own.timings } };
      },
    };
    const front = createBankFront({
      rungs,
      bank: view,
      fetch: fetchFn,
      now: clock,
      reconnectMs,
      onThroughput: (kbps) => handlers.onThroughput(kbps),
      onStopped: endStopped,
      onSessionLost: endLost,
    });
    const requests = createBankRequests({
      bank: view,
      front,
      levelOfRung: bankLevelOfRung,
      rungOfLevel: bankRungOfLevel,
      wrongRung: (level) => engine.bankWrongRung(level),
      onRelease: () => {
        releasing = true; // the pass that follows is the release, not a bypass
        closeEpisode();
      }, // before the inner loader's `load`: the decider calls it first
      firstByteMs: policy.maxTimeToFirstByteMs,
      loadMs: policy.maxLoadTimeMs,
    });
    episode = {
      front,
      requests,
      tick: setInterval(bankStep, BANK_TICK_MS),
      seal: () => {
        sealed = true;
      },
      rungIds: rungs.map((r) => r.id),
      candidates: rungs.map((r) => ({ id: r.id, rate: r.kbps })),
    };
    requests.setMode('armed');
    front.arm();
    phase = 'armed';
  };

  // Every condition of decision 2 but the stretch's time: the playlists are held, film ahead is below F, hls.js
  // has no media request in flight, and the bank covers playhead − 30 s to the end of the buffered range.
  const readyToTurnOn = (input) =>
    store.allPlaylistsHeld(episode.rungIds) &&
    filmAheadNow() < needOf(input) &&
    loadingFrag === null &&
    store.covers(video.currentTime, bufferEnd(video.currentTime, pending));

  const turnOn = (input) => {
    const { front, requests } = episode;
    requests.update({ filmAhead: filmAheadNow(), need: needOf(input) });
    // At the first film after the held run, at hls.js's rung (the front's own choice when that is above 480p).
    front.start({
      seq: store.nextAfterRun(Math.max(0, video.currentTime - KEEP_BEHIND_S)),
      rung: rungOfLevelIndex(fetched),
    });
    engine.bankOn({
      levelAt: (seconds) => {
        const id = store.rungAt(seconds);
        return id === null ? null : bankLevelOfRung(id);
      },
      frontLevel: () => bankLevelOfRung(front.position().rung),
    });
    requests.setMode('on');
    phase = 'on';
    lastChoiceAt = null;
  };

  // Decision 7's timing: the first choice after MIN_MEASURED_S of measured fetching, then once a minute, never
  // inside a stretch, at F or at the limit; the next stretch is chosen afresh.
  const chooseFrontRung = (input) => {
    if (input.stretchBegun) {
      lastChoiceAt = null;
      return;
    }
    const { front, requests, candidates } = episode;
    const filmAhead = filmAheadNow();
    const need = needOf(input);
    const measured = front.window();
    if (store.atLimit() || filmAhead >= need || measured.measuredWallS < MIN_MEASURED_S) return;
    if (lastChoiceAt !== null && clock() - lastChoiceAt < BANK_CHOICE_EVERY_MS) return;
    lastChoiceAt = clock();
    const choice = chooseBankRung({
      filmAhead,
      timeToStretch: input.timeToStretch,
      need,
      stretchBegun: false,
      atLimit: false,
      w: measured.w,
      d: measured.d,
      measuredWallS: measured.measuredWallS,
      rate: measured.rate,
      candidates,
    });
    if (choice === null) return;
    if (choice.id !== requests.chosenRung()) handlers.onBankRungChange?.();
    requests.setRung(choice.id);
  };

  // A seek inside held film moves the front past the run holding the target, so a gap it was filling is left. The
  // move cuts the fetch under way, which hls.js may be waiting on, so it waits until no request of hls.js waits on
  // the bank; the need stays 0 inside a gap meanwhile, and `endGap` then restores it.
  let pendingSeek = false;
  const settleSeek = () => {
    pendingSeek = false;
    const { front, requests } = episode;
    const next = store.nextAfterRun(video.currentTime);
    if (next !== null && next !== front.position().seq) {
      front.start({ seq: next, rung: front.chosenRung() });
    }
    requests.endGap();
  };

  // Moves the bank along its phases; run on the look-ahead's input, the playhead, and every fragment event.
  const bankStep = () => {
    if (destroyed || store === null) return;
    const input = lookahead;
    for (const front of finishing) if (front.isFinished()) finishing.delete(front);
    if (phase === 'idle') {
      if (within(input, BANK_ARM_WITHIN_S)) startEpisode();
      return;
    }
    store.dropBehind(video.currentTime); // keeps playhead − 30 s, so a refused append fetched again is answered
    if (phase === 'armed') {
      if (!within(input, BANK_ARM_WITHIN_S)) closeEpisode();
      else if (within(input, BANK_ON_WITHIN_S) && readyToTurnOn(input)) turnOn(input);
    } else if (phase === 'on') {
      // Off when the stretch is passed and none is within 20 minutes, or when the look-ahead lapses: the front
      // finishes the fetch in flight (answering whoever waits on it) and starts nothing new, held film is still
      // answered, and the first request for film not held releases (the decider).
      if (input === null || (!input.stretchBegun && input.timeToStretch > BANK_ON_WITHIN_S)) {
        episode.requests.update({ filmAhead: filmAheadNow(), need: 0 });
        episode.front.finish();
        episode.requests.setMode('turningOff');
        phase = 'turningOff';
      } else {
        if (pendingSeek && waiting.size === 0) settleSeek();
        episode.requests.update({ filmAhead: filmAheadNow(), need: needOf(input) });
        chooseFrontRung(input);
      }
    }
  };

  // A seek: a forward one beyond what is held releases at once and clears (and the bank is armed again with a
  // stretch near); inside held film nothing is released, and while turning off a seek back to before held film
  // releases on its first request, as any request for film not held does.
  const bankSeek = () => {
    if (episode === null) return;
    const target = video.currentTime;
    const verdict = store.seek(target);
    if (verdict === 'inside' && phase === 'on') {
      if (waiting.size === 0) settleSeek();
      else pendingSeek = true;
      return;
    }
    episode.requests.endGap();
    if (verdict !== 'beyond') return;
    if (phase === 'armed') {
      store.clear();
    } else {
      closeEpisode();
      bankStep();
    }
  };

  hls.loadSource(session.masterUrl);
  hls.attachMedia(video);

  const engine = {
    /**
     * The look-ahead's input (outside-bank-plan.md, decision 2): `{ need, timeToStretch, stretchBegun }`, or null
     * when no stretch is near or the look-ahead has lapsed. `need` is F, the film ahead wanted before the
     * stretch, in seconds; `timeToStretch` is the seconds to the stretch (0 or less once it has begun);
     * `stretchBegun` says it has. Call it whenever it changes and about once a second: the bank is armed with a
     * stretch within 30 minutes, turned on within 20 (when every playlist up to 480p is held, film ahead is
     * below F, no fragment is in flight and the bank covers the buffer), and turned off when the stretch is
     * passed with none within 20 minutes, or on null. Does nothing with the rung pinned (Auto off).
     */
    bankInput(input) {
      lookahead = input;
      if (!destroyed && store !== null) recordInput(input);
      bankStep();
    },
    /**
     * The film bank turns on (outside-bank-plan.md, decision 3). `lookup` is `{ levelAt(seconds), frontLevel() }`,
     * both answering an hls.js level index: `levelAt` the level of the held fragment that covers a film second
     * (null when none is held), `frontLevel` the level the bank's front fetches at. A re-bank under way is undone,
     * then hls.js is put on the manual level for the film after the buffered range at the playhead. Until
     * `bankRelease` the step gate, floor hold, champagne ceiling, re-bank and every gate lowering are suspended.
     * Calling it while on only replaces the lookup.
     */
    bankOn(lookup) {
      if (destroyed) return;
      const wasOn = bank !== null;
      bank = lookup;
      if (wasOn) return;
      pending = null;
      undoIfRebanking(); // the floor is written before the manual level
      holdBankLevelAtPlayhead();
    },
    /**
     * The bank failed a request as `bank-rung` (a fragment held at another rung): hls.js is put on `level`, the
     * rung held, before the error is raised, so the retry loads it there. The error's `fragmentError` is taken
     * back by the ERROR handler. Does nothing while the bank is off.
     */
    bankWrongRung(level) {
      if (!destroyed && bank) hls.loadLevel = level;
    },
    /**
     * The bank turns off: hls.js is back on Auto, the floor and the cap are written again, and the step gate
     * starts at `fetchedLevel`, the level the bank last fetched (the champagne ceiling still clamps it), so the
     * climb from there is one level a minute. `fetchedLevel` is required and is the bank's own: the level hls.js is
     * on is only the last one this engine wrote, so B5b must pass it. Throws on anything but a level index.
     * Does nothing while the bank is off.
     */
    bankRelease(fetchedLevel) {
      if (!Number.isInteger(fetchedLevel)) {
        throw new Error(`bankRelease needs the level the bank last fetched, got ${fetchedLevel}`);
      }
      if (destroyed || !bank) return;
      bank = null;
      pending = null;
      hls.loadLevel = -1;
      floorSet = null; // written again whatever it was
      applied = null;
      writeFloor();
      cap(gate.start(fetchedLevel));
    },
    destroy() {
      destroyed = true;
      bank = null;
      clearTimeout(timer);
      timer = null;
      stopHoldTimer();
      undoRebank();
      window.removeEventListener('online', onOnline);
      video.removeEventListener('waiting', onWaiting);
      video.removeEventListener('timeupdate', onTimeUpdate);
      video.removeEventListener('seeking', onSeeking);
      video.removeEventListener('play', followHole);
      video.removeEventListener('pause', followHole);
      hls.destroy(); // destroys the loaders first: a request waiting on the front ends with no timeout
      if (episode !== null) {
        clearInterval(episode.tick);
        episode.front.stop(); // a destroy is the full cut
        episode.requests.setMode('off');
        episode = null;
      }
      for (const front of finishing) front.stop();
      finishing.clear();
      store?.clear();
    },
  };
  return engine;
}
