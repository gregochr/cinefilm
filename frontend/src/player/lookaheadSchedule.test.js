import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { feedText, inputAtStart, northTrack } from '../test/lookaheadFixtures.js';
import {
  ASK_EVERY_MS,
  FEED_ENGINE_EVERY_MS,
  createLookaheadSchedule,
} from './lookaheadSchedule.js';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

const settle = () => vi.advanceTimersByTimeAsync(0);
const engineOf = () => ({ bankInput: vi.fn() });
const inputsOf = (engine) => engine.bankInput.mock.calls.map(([input]) => input);

/**
 * A schedule on fakes, with an engine attached. Each ask is a promise the test settles; `honour` makes an abort settle it with null, as the
 * real frame does. `maps` is the queue of what each map load answers (a value, or a promise).
 */
function build({ honour = true, maps = [[northTrack()]], attached = true } = {}) {
  const asks = [];
  const ask = vi.fn((signal) => {
    const entry = { signal };
    entry.promise = new Promise((resolve, reject) => {
      entry.resolve = resolve;
      entry.reject = reject;
    });
    if (honour) signal.addEventListener('abort', () => entry.resolve(null));
    asks.push(entry);
    return entry.promise;
  });
  const mapCalls = [];
  const loadMap = vi.fn((signal) => {
    const next = maps[Math.min(mapCalls.length, maps.length - 1)];
    const entry = { signal };
    entry.promise = next instanceof Promise ? next : Promise.resolve(next);
    mapCalls.push(entry);
    return entry.promise;
  });
  const schedule = createLookaheadSchedule({ ask, loadMap });
  const engine = engineOf(); // attached unless a test says otherwise: nothing is asked without one
  if (attached) schedule.attach(engine);
  return { schedule, ask, asks, loadMap, mapCalls, engine };
}

const answer = async (entry, text) => {
  entry.resolve(text);
  await settle();
};

describe('when it asks', () => {
  it('asks nothing until it is begun, and a trigger or a new engine changes nothing', async () => {
    const { schedule, ask, loadMap } = build();
    const engine = engineOf();
    schedule.attach(engine);
    schedule.trigger();
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS * 3);
    expect(ask).not.toHaveBeenCalled();
    expect(loadMap).not.toHaveBeenCalled();
    expect(engine.bankInput).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('asks once at the start, with a signal, and a second start asks nothing more', async () => {
    const { schedule, ask, asks } = build();
    schedule.begin();
    schedule.begin();
    await settle();
    expect(ask).toHaveBeenCalledTimes(1);
    expect(asks[0].signal.aborted).toBe(false);
  });

  it('asks every 30 s after an answer, and not before', async () => {
    const { schedule, ask, asks } = build();
    schedule.begin();
    await answer(asks[0], feedText());
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS - 1);
    expect(ask).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(ask).toHaveBeenCalledTimes(2);
    await answer(asks[1], feedText());
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS);
    expect(ask).toHaveBeenCalledTimes(3);
  });

  it.each([
    ['null', () => null],
    ['text that is not a fix', () => 'cb({"latitude":"oops"});'],
    ['text that is not text', () => 42],
  ])('stops the cycle after %s and asks again only on a trigger', async (_name, value) => {
    const { schedule, ask, asks } = build();
    const engine = engineOf();
    schedule.attach(engine);
    schedule.begin();
    await answer(asks[0], value());
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS * 5);
    expect(ask).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
    expect(engine.bankInput).not.toHaveBeenCalled(); // nothing was known, so nothing is handed over
    schedule.trigger();
    expect(ask).toHaveBeenCalledTimes(2);
  });

  it('treats a rejected ask as no answer', async () => {
    const { schedule, ask, asks } = build();
    schedule.begin();
    asks[0].reject(new Error('refused'));
    await settle();
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS * 2);
    expect(ask).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('asks on a trigger at once, and the 30 s count starts again from that answer', async () => {
    const { schedule, ask, asks } = build();
    schedule.begin();
    await answer(asks[0], feedText());
    await vi.advanceTimersByTimeAsync(10_000);
    schedule.trigger();
    expect(ask).toHaveBeenCalledTimes(2);
    await answer(asks[1], feedText());
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS - 1);
    expect(ask).toHaveBeenCalledTimes(2); // the first answer's timer is gone
    await vi.advanceTimersByTimeAsync(1);
    expect(ask).toHaveBeenCalledTimes(3);
  });
});

describe('one ask at a time', () => {
  it('drops an ask in flight on a network change and asks again only once it has settled', async () => {
    const { schedule, ask, asks } = build({ honour: false });
    schedule.begin();
    schedule.trigger();
    expect(asks[0].signal.aborted).toBe(true);
    expect(ask).toHaveBeenCalledTimes(1); // the old one has not settled: no second ask beside it
    await answer(asks[0], null);
    expect(ask).toHaveBeenCalledTimes(2);
  });

  it('ignores an answer that arrives after the network-change re-ask was asked for', async () => {
    const { schedule, ask, asks, loadMap } = build({ honour: false });
    const engine = engineOf();
    schedule.attach(engine);
    schedule.begin();
    schedule.trigger();
    await answer(asks[0], feedText()); // from the old network
    expect(loadMap).not.toHaveBeenCalled();
    expect(engine.bankInput).not.toHaveBeenCalled();
    expect(ask).toHaveBeenCalledTimes(2);
    await answer(asks[1], feedText());
    expect(loadMap).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(2); // the 30 s cycle and the 1 s tick, from the new answer only
  });

  it('asks once more, not twice, for two triggers close together', async () => {
    const { schedule, ask, asks } = build();
    schedule.begin();
    schedule.trigger();
    schedule.trigger();
    await settle();
    expect(asks[0].signal.aborted).toBe(true);
    expect(ask).toHaveBeenCalledTimes(2);
    expect(asks[1].signal.aborted).toBe(false);
  });
});

describe('the map', () => {
  it('is not loaded before a fix parses, and once only across many answers', async () => {
    const { schedule, loadMap, asks } = build();
    schedule.begin();
    await answer(asks[0], null);
    schedule.trigger();
    await answer(asks[1], 'nonsense');
    expect(loadMap).not.toHaveBeenCalled();
    schedule.trigger();
    await answer(asks[2], feedText());
    expect(loadMap).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS);
    await answer(asks[3], feedText());
    schedule.trigger();
    await answer(asks[4], feedText());
    expect(loadMap).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['an empty map', [[]]],
    ['a map that is not a list', [null]],
  ])('is tried again only on a trigger after %s', async (_name, maps) => {
    const { schedule, loadMap, asks } = build({ maps: [...maps, [northTrack()]] });
    const engine = engineOf();
    schedule.attach(engine);
    schedule.begin();
    await answer(asks[0], feedText());
    await vi.advanceTimersByTimeAsync(FEED_ENGINE_EVERY_MS * 3);
    expect(new Set(inputsOf(engine))).toEqual(new Set([null])); // no map, so no input yet
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS);
    await answer(asks[1], feedText()); // the cycle's own answer does not try again
    expect(loadMap).toHaveBeenCalledTimes(1);
    schedule.trigger();
    await answer(asks[2], feedText());
    expect(loadMap).toHaveBeenCalledTimes(2);
    expect(inputsOf(engine).at(-1)).toMatchObject({ need: 300 });
  });

  it('is tried again on a trigger after a load that rejects', async () => {
    const { schedule, loadMap, asks } = build();
    loadMap.mockRejectedValueOnce(new Error('down'));
    schedule.begin();
    await answer(asks[0], feedText());
    schedule.trigger();
    await answer(asks[1], feedText());
    expect(loadMap).toHaveBeenCalledTimes(2);
  });

  it('is not loaded twice when a trigger comes while it loads', async () => {
    let finish;
    const slow = new Promise((resolve) => {
      finish = resolve;
    });
    const { schedule, loadMap, asks } = build({ maps: [slow] });
    schedule.begin();
    await answer(asks[0], feedText());
    schedule.trigger();
    await answer(asks[1], feedText());
    expect(loadMap).toHaveBeenCalledTimes(1);
    finish([northTrack()]);
    await settle();
  });

  it('is kept when it is stopped and begun again: one load per playback', async () => {
    const { schedule, loadMap, asks } = build();
    schedule.begin();
    await answer(asks[0], feedText());
    schedule.stop();
    schedule.begin();
    await answer(asks[1], feedText());
    expect(loadMap).toHaveBeenCalledTimes(1);
  });

  it('places an answer that arrived while the map was loading, moved on by the wait', async () => {
    let finish;
    const slow = new Promise((resolve) => {
      finish = resolve;
    });
    const { schedule, asks } = build({ maps: [slow] });
    const engine = engineOf();
    schedule.attach(engine);
    schedule.begin();
    await answer(asks[0], feedText(0, { speed: 10 }));
    await vi.advanceTimersByTimeAsync(10_000); // the map is slow
    expect(new Set(inputsOf(engine))).toEqual(new Set([null]));
    finish([northTrack()]);
    await settle();
    // The fix is 10 s old by now and the train does 10 m/s: 100 m on, one point, 10 s further along the track.
    expect(inputsOf(engine).at(-1)).toEqual(inputAtStart(10));
  });

  it('gives no input when the map arrives after the last fix has gone out of date', async () => {
    let finish;
    const slow = new Promise((resolve) => {
      finish = resolve;
    });
    const { schedule, asks } = build({ maps: [slow] });
    const engine = engineOf();
    schedule.attach(engine);
    schedule.begin();
    await answer(asks[0], feedText());
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS);
    await answer(asks[1], null); // the feed has stopped answering
    await vi.advanceTimersByTimeAsync(40_000);
    finish([northTrack()]);
    await settle();
    expect(inputsOf(engine).at(-1)).toBeNull();
  });

  it('is cancelled by a stop, and a map that arrives later changes nothing', async () => {
    let finish;
    const slow = new Promise((resolve) => {
      finish = resolve;
    });
    const { schedule, asks, mapCalls } = build({ maps: [slow] });
    const engine = engineOf();
    schedule.attach(engine);
    schedule.begin();
    await answer(asks[0], feedText());
    schedule.stop();
    expect(mapCalls[0].signal.aborted).toBe(true);
    engine.bankInput.mockClear();
    finish([northTrack()]);
    await settle();
    expect(engine.bankInput).not.toHaveBeenCalled();
  });
});

describe('feeding the engine', () => {
  it('hands it the input about once a second, counting down to the stretch', async () => {
    const { schedule, asks } = build();
    const engine = engineOf();
    schedule.attach(engine);
    schedule.begin();
    await answer(asks[0], feedText());
    expect(inputsOf(engine)).toEqual([inputAtStart(0)]); // placed at once
    await vi.advanceTimersByTimeAsync(FEED_ENGINE_EVERY_MS);
    expect(inputsOf(engine).at(-1)).toEqual(inputAtStart(1));
    await vi.advanceTimersByTimeAsync(FEED_ENGINE_EVERY_MS * 4);
    expect(inputsOf(engine).at(-1)).toEqual(inputAtStart(5));
    expect(engine.bankInput).toHaveBeenCalledTimes(6);
  });

  it('gives a rebuilt engine the current input at once, and the old one nothing more', async () => {
    const { schedule, asks } = build();
    const first = engineOf();
    schedule.attach(first);
    schedule.begin();
    await answer(asks[0], feedText());
    await vi.advanceTimersByTimeAsync(3000);
    schedule.detach(first);
    const second = engineOf();
    schedule.attach(second);
    expect(second.bankInput).toHaveBeenCalledTimes(1);
    expect(second.bankInput).toHaveBeenCalledWith(inputAtStart(3));
    first.bankInput.mockClear();
    await vi.advanceTimersByTimeAsync(FEED_ENGINE_EVERY_MS);
    expect(first.bankInput).not.toHaveBeenCalled();
    expect(second.bankInput).toHaveBeenCalledTimes(2);
  });

  it('hands a new engine nothing before the first answer', async () => {
    const { schedule } = build();
    schedule.begin();
    const engine = engineOf();
    schedule.attach(engine);
    await vi.advanceTimersByTimeAsync(FEED_ENGINE_EVERY_MS * 3);
    expect(engine.bankInput).not.toHaveBeenCalled();
  });

  it('keeps the engine it has when another, older one is detached late', async () => {
    const { schedule, asks } = build();
    const old = engineOf();
    const current = engineOf();
    schedule.attach(old);
    schedule.attach(current);
    schedule.detach(old);
    schedule.begin();
    await answer(asks[0], feedText());
    expect(current.bankInput).toHaveBeenCalledTimes(1);
  });

  it('lets the engine fail without breaking the schedule', async () => {
    const { schedule, asks, ask } = build();
    const engine = {
      bankInput: vi.fn(() => {
        throw new Error('engine gone');
      }),
    };
    schedule.attach(engine);
    schedule.begin();
    await answer(asks[0], feedText());
    await vi.advanceTimersByTimeAsync(FEED_ENGINE_EVERY_MS * 2);
    expect(engine.bankInput).toHaveBeenCalledTimes(3);
    schedule.stop();
    expect(engine.bankInput).toHaveBeenLastCalledWith(null);
    expect(ask).toHaveBeenCalledTimes(1);
  });

  it('hands the input null once the feed has been silent for 5 minutes', async () => {
    const { schedule, asks } = build();
    const engine = engineOf();
    schedule.attach(engine);
    schedule.begin();
    await answer(asks[0], feedText());
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS);
    await answer(asks[1], null);
    await vi.advanceTimersByTimeAsync(300_000);
    expect(inputsOf(engine).at(-1)).toBeNull();
  });
});

describe('stopping', () => {
  it('cancels the ask in flight, clears the timers and sends the engine null', async () => {
    const { schedule, asks } = build({ honour: false });
    const engine = engineOf();
    schedule.attach(engine);
    schedule.begin();
    schedule.stop();
    expect(asks[0].signal.aborted).toBe(true);
    expect(engine.bankInput).toHaveBeenCalledOnce();
    expect(engine.bankInput).toHaveBeenCalledWith(null);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('clears the 30 s and 1 s timers after answers', async () => {
    const { schedule, asks } = build();
    const engine = engineOf();
    schedule.attach(engine);
    schedule.begin();
    await answer(asks[0], feedText());
    expect(vi.getTimerCount()).toBe(2);
    schedule.stop();
    expect(vi.getTimerCount()).toBe(0);
    engine.bankInput.mockClear();
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS * 2);
    expect(engine.bankInput).not.toHaveBeenCalled();
  });

  it('changes nothing when a late answer arrives after the stop', async () => {
    const { schedule, ask, asks, loadMap } = build({ honour: false });
    const engine = engineOf();
    schedule.attach(engine);
    schedule.begin();
    schedule.stop();
    engine.bankInput.mockClear();
    await answer(asks[0], feedText());
    expect(loadMap).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
    expect(engine.bankInput).not.toHaveBeenCalled();
    expect(ask).toHaveBeenCalledTimes(1);
  });

  it('does not ask again after a stop that came with a re-ask pending', async () => {
    const { schedule, ask, asks } = build({ honour: false });
    schedule.begin();
    schedule.trigger();
    schedule.stop();
    await answer(asks[0], null);
    expect(ask).toHaveBeenCalledTimes(1);
  });

  it('does nothing when it was never begun, and stays stoppable twice', () => {
    const { schedule } = build();
    const engine = engineOf();
    schedule.attach(engine);
    schedule.stop();
    schedule.stop();
    expect(engine.bankInput).not.toHaveBeenCalled();
  });

  it('survives a stop whose engine is already gone, and sends nothing to a detached one', async () => {
    const { schedule, asks } = build();
    const gone = engineOf();
    gone.bankInput.mockImplementation(() => {
      throw new Error('destroyed');
    });
    schedule.attach(gone);
    schedule.begin();
    expect(() => schedule.stop()).not.toThrow();
    schedule.begin();
    const other = engineOf();
    schedule.attach(other);
    schedule.detach(other);
    schedule.stop();
    expect(other.bankInput).not.toHaveBeenCalled();
    expect(asks).toHaveLength(2);
  });

  it('can begin again after a stop, with a fresh ask', async () => {
    const { schedule, ask } = build();
    schedule.begin();
    schedule.stop();
    schedule.begin();
    expect(ask).toHaveBeenCalledTimes(2);
  });
});

describe('once the train is known', () => {
  it('goes on asking every 30 s after a null, and matches a fix that parses later', async () => {
    const { schedule, ask, asks, engine } = build();
    schedule.begin();
    await answer(asks[0], feedText());
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS);
    await answer(asks[1], null);
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS);
    expect(ask).toHaveBeenCalledTimes(3);
    engine.bankInput.mockClear();
    await answer(asks[2], feedText(300));
    // 300 m on at 10 m/s: 30 s further along the track.
    expect(inputsOf(engine)[0].timeToStretch).toBeCloseTo(570, 2);
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS);
    expect(ask).toHaveBeenCalledTimes(4);
  });

  it.each([
    ['an answer that is not a fix', 'cb({"latitude":"oops"});'],
    ['an ask that rejects', undefined],
  ])('goes on after %s too', async (_name, value) => {
    const { schedule, ask, asks } = build();
    schedule.begin();
    await answer(asks[0], feedText());
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS);
    if (value) await answer(asks[1], value);
    else {
      asks[1].reject(new Error('refused'));
      await settle();
    }
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS);
    expect(ask).toHaveBeenCalledTimes(3);
  });

  it('asks again exactly 30,000 ms after a null, not 29,999', async () => {
    const { schedule, ask, asks } = build();
    schedule.begin();
    await answer(asks[0], feedText());
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS);
    await answer(asks[1], null);
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS - 1);
    expect(ask).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(ask).toHaveBeenCalledTimes(3);
  });

  it('still ends the cycle at a null when no fix has parsed in this playback', async () => {
    const { schedule, ask, asks } = build();
    schedule.begin();
    await answer(asks[0], null);
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS * 3);
    expect(ask).toHaveBeenCalledTimes(1);
  });

  it('forgets that a fix parsed when the playback begins again', async () => {
    const { schedule, ask, asks } = build();
    schedule.begin();
    await answer(asks[0], feedText());
    schedule.stop();
    schedule.begin();
    await answer(asks[1], null);
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS * 3);
    expect(ask).toHaveBeenCalledTimes(2);
  });
});

describe('a handler that throws', () => {
  const throwing = () => ({
    noteMatch: () => {
      throw new Error('broken');
    },
    input: () => null,
  });
  const unhandled = vi.fn();
  beforeEach(() => {
    unhandled.mockClear();
    process.on('unhandledRejection', unhandled);
  });
  afterEach(() => process.off('unhandledRejection', unhandled));

  it('leaves no unhandled rejection and no ask held when the answer cannot be placed', async () => {
    const ask = vi.fn(() => Promise.resolve(feedText()));
    const loadMap = vi.fn(() => Promise.resolve([northTrack()]));
    const schedule = createLookaheadSchedule({ ask, loadMap, createInput: throwing });
    schedule.attach(engineOf());
    schedule.begin();
    await settle(); // the answer, then the map, whose placing throws
    await settle();
    schedule.trigger(); // the ask is not held: this asks at once
    expect(ask).toHaveBeenCalledTimes(2);
    await settle();
    expect(unhandled).not.toHaveBeenCalled();
  });

  it('leaves no unhandled rejection, and asks again on the next trigger, when an ask throws as it starts', async () => {
    let calls = 0;
    const ask = vi.fn(() => {
      calls += 1;
      if (calls === 2) throw new Error('no frame');
      return Promise.resolve(null);
    });
    const schedule = createLookaheadSchedule({ ask, loadMap: () => Promise.resolve([]) });
    schedule.attach(engineOf());
    schedule.begin();
    schedule.trigger(); // aborted: the settled ask starts another, which throws as it starts
    await settle();
    await settle();
    expect(ask).toHaveBeenCalledTimes(2);
    schedule.trigger(); // nothing is held: this asks at once
    expect(ask).toHaveBeenCalledTimes(3);
    await settle();
    expect(unhandled).not.toHaveBeenCalled();
  });

  it('loads the map again on a later trigger when the load throws as it starts', async () => {
    let calls = 0;
    const loadMap = vi.fn(() => {
      calls += 1;
      if (calls === 1) throw new Error('no fetch');
      return Promise.resolve([northTrack()]);
    });
    const ask = vi.fn(() => Promise.resolve(feedText()));
    const schedule = createLookaheadSchedule({ ask, loadMap });
    const engine = engineOf();
    schedule.attach(engine);
    schedule.begin();
    await settle();
    await settle();
    expect(loadMap).toHaveBeenCalledTimes(1);
    schedule.trigger();
    await settle();
    await settle();
    expect(loadMap).toHaveBeenCalledTimes(2);
    expect(engine.bankInput).toHaveBeenLastCalledWith(expect.objectContaining({ need: 300 }));
    expect(unhandled).not.toHaveBeenCalled();
  });

  it('carries on 30 s later when the 30 s timer’s ask throws as it starts', async () => {
    let calls = 0;
    const ask = vi.fn(() => {
      calls += 1;
      if (calls === 2) throw new Error('no frame');
      return Promise.resolve(feedText());
    });
    const schedule = createLookaheadSchedule({
      ask,
      loadMap: () => Promise.resolve([northTrack()]),
    });
    schedule.attach(engineOf());
    schedule.begin();
    await settle();
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS); // the second ask throws
    expect(ask).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS);
    expect(ask).toHaveBeenCalledTimes(3);
    expect(unhandled).not.toHaveBeenCalled();
  });
});

describe('only with an engine that can take the input', () => {
  it('ignores an engine without bankInput (the native player): no ask, no map, no throw', async () => {
    const { schedule, ask, loadMap } = build({ attached: false });
    schedule.begin();
    expect(() => schedule.attach({ destroy: vi.fn(), kind: 'native' })).not.toThrow();
    expect(() => schedule.attach(null)).not.toThrow();
    schedule.trigger();
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS * 3);
    expect(ask).not.toHaveBeenCalled();
    expect(loadMap).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('asks nothing at begin without an engine, and asks at once when a capable one comes', async () => {
    const { schedule, ask } = build({ attached: false });
    schedule.begin();
    await settle();
    expect(ask).not.toHaveBeenCalled();
    schedule.attach(engineOf());
    expect(ask).toHaveBeenCalledTimes(1);
  });

  it('pauses on a detach mid-cycle: no further asks, no ticks, the ask in flight cancelled', async () => {
    const { schedule, ask, asks, engine } = build({ honour: false });
    schedule.begin();
    await answer(asks[0], feedText());
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS);
    expect(ask).toHaveBeenCalledTimes(2);
    schedule.detach(engine);
    expect(asks[1].signal.aborted).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
    engine.bankInput.mockClear();
    await answer(asks[1], feedText()); // late: changes nothing
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS * 3);
    expect(ask).toHaveBeenCalledTimes(2);
    expect(engine.bankInput).not.toHaveBeenCalled();
  });

  it('pauses a map load on a detach', async () => {
    let finish;
    const slow = new Promise((resolve) => {
      finish = resolve;
    });
    const { schedule, asks, mapCalls, engine } = build({ maps: [slow] });
    schedule.begin();
    await answer(asks[0], feedText());
    schedule.detach(engine);
    expect(mapCalls[0].signal.aborted).toBe(true);
    engine.bankInput.mockClear();
    finish([northTrack()]);
    await settle();
    expect(engine.bankInput).not.toHaveBeenCalled();
  });

  it('on a re-attach asks at once and hands the current input at once, then goes on', async () => {
    const { schedule, ask, asks, engine } = build();
    schedule.begin();
    await answer(asks[0], feedText());
    await vi.advanceTimersByTimeAsync(5000);
    schedule.detach(engine);
    await vi.advanceTimersByTimeAsync(20_000);
    const next = engineOf();
    schedule.attach(next);
    expect(ask).toHaveBeenCalledTimes(2);
    expect(next.bankInput).toHaveBeenCalledTimes(1);
    expect(next.bankInput).toHaveBeenCalledWith(inputAtStart(25));
    await answer(asks[1], feedText(250));
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS);
    expect(ask).toHaveBeenCalledTimes(3);
  });

  it('does not start a second ask when an engine is attached while one is in flight', () => {
    const { schedule, ask } = build();
    schedule.begin();
    schedule.attach(engineOf());
    expect(ask).toHaveBeenCalledTimes(1);
  });

  it('loads the map again after a re-attach when it had not loaded', async () => {
    const { schedule, loadMap, asks, engine } = build({ maps: [[], [northTrack()]] });
    schedule.begin();
    await answer(asks[0], feedText());
    schedule.detach(engine);
    schedule.attach(engineOf());
    await answer(asks[1], feedText());
    expect(loadMap).toHaveBeenCalledTimes(2);
  });

  it('ignores a detach of another engine, of null, and of an engine before it began', async () => {
    const { schedule, ask, asks, engine } = build();
    schedule.detach(engine); // not running: nothing to pause
    schedule.attach(engine);
    schedule.begin();
    schedule.detach(engineOf());
    schedule.detach(null);
    await answer(asks[0], feedText());
    await vi.advanceTimersByTimeAsync(ASK_EVERY_MS);
    expect(ask).toHaveBeenCalledTimes(2);
  });
});
