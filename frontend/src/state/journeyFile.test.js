import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { readRepoJson } from '../test/repoFile.js';
import { MAX_REQUEST_CHARS, RANGES, readJourneyLog } from './journeyFile.js';

const EXAMPLE_LOG = resolve(import.meta.dirname, '../test/fixtures/journey-log-example.jsonl');
const exampleLog = () => readFileSync(EXAMPLE_LOG, 'utf8');

const T0 = 1790000000000;
const START = {
  event: 'start',
  tool: 'train-feed-logger',
  version: 3,
  t: T0,
  macos: 'TestOS 1.2',
  travel_class: 'first',
  device: {
    model: 'Testbook1,1',
    cpu: 'Test Chip One',
    cores: 8,
    threads: 8,
    memory_bytes: 17179869184,
  },
};
const V2_START = { event: 'start', tool: 'train-feed-logger', version: 2, t: T0 };

const join = (...lines) =>
  lines.map((l) => (typeof l === 'string' ? l : JSON.stringify(l))).join('\n');
const roundLine = (n, extra = {}) => ({ t: T0 + n * 30000, round: n, ...extra });
const read = (...lines) => readJourneyLog(join(START, ...lines), 'test.jsonl');
const link = (extra = {}) => ({
  index: '100',
  device_type: 'TestModemType',
  device_subtype: 'TestSubtype',
  device_state: 'up',
  link_state: 'available',
  technology: 'lte',
  rssi: '-70',
  operator_id: '00123',
  umts_info: { cellid: '123456' },
  ...extra,
});
const feedOf = (links) => ({ feed: { connectivity: { links } } });
const leftOut = (result) =>
  Object.fromEntries(result.summary.leftOut.map((o) => [o.field, o.count]));

/** Sets `value` at `path` inside a new object. */
function nested(path, value) {
  return path.reduceRight((inner, key) => ({ [key]: inner }), value);
}
/** One round carrying `value` at `path`; also `online` is not needed for the others. */
const roundWith = (path, value) => roundLine(1, nested(path, value));

describe('the contract worked example', () => {
  it('reads to exactly the backend example', () => {
    const expected = readRepoJson('backend/src/test/resources/journey/import-request-example.json');
    const result = readJourneyLog(exampleLog(), 'test-feed-2026-09-21-1000.jsonl');
    expect(result.ok).toBe(true);
    expect(result.request).toEqual(expected);
  });

  it('summarises the example field by field', () => {
    const { summary } = readJourneyLog(exampleLog(), 'test-feed-2026-09-21-1000.jsonl');
    expect(summary).toEqual({
      rounds: 6,
      from: 1790000030000,
      to: 1790000180000,
      units: ['Unit-001', 'Unit-002'],
      modems: [100, 101],
      loggerVersion: 3,
      hasDevice: true,
      travelClass: 'standard',
      unreadableLines: 0,
      leftOut: [],
    });
  });

  it('lets none of the laptop, the feed addresses or the bundle leave the file', () => {
    const result = readJourneyLog(exampleLog(), 'test-feed-2026-09-21-1000.jsonl');
    const sent = JSON.stringify([result.request, result.summary]);
    for (const secret of [
      '02:00:00:00:00:01',
      '192.0.2.',
      '198.51.100.',
      'TestNetwork',
      'apninfo',
      'test.apn',
      '"lac"',
      'BUNDLE-TEST-77',
      'charge',
      'discharging',
      'system_id',
      'TestPortal',
      'users_text',
      'bundleid',
      'test-server',
      'example.invalid',
      'generate_204',
      'api/jsonp',
      '802.11ax',
      'signal_noise',
    ]) {
      expect(sent).not.toContain(secret);
    }
  });

  it('takes a file with or without the stop line', () => {
    const lines = exampleLog().trimEnd().split('\n');
    const without = readJourneyLog(lines.slice(0, -1).join('\n'), 'x');
    expect(without.ok).toBe(true);
    expect(without.summary.rounds).toBe(6);
  });
});

describe('a version 2 file', () => {
  it('has no device, travel class, operating system or laptop columns', () => {
    const result = readJourneyLog(
      join(V2_START, roundLine(1, { probe: { ms: 20, code: 204 } })),
      'old.jsonl',
    );
    expect(result.request).toEqual({
      runStartedMs: T0,
      loggerVersion: 2,
      sourceFile: 'old.jsonl',
      samples: [{ round: 1, at: T0 + 30000, probeMs: 20, probeCode: 204 }],
    });
    expect(result.summary).toMatchObject({
      hasDevice: false,
      travelClass: null,
      loggerVersion: 2,
      leftOut: [],
    });
  });
});

describe('refusals', () => {
  it.each([
    ['empty', ''],
    ['empty', '\n  \n'],
    ['empty', 'not json\n{cut'],
  ])('refuses %s text', (problem, text) => {
    expect(readJourneyLog(text, 'f')).toEqual({ ok: false, problem });
  });

  it('refuses text that is not a string as empty', () => {
    expect(readJourneyLog(undefined, 'f')).toEqual({ ok: false, problem: 'empty' });
  });

  it('refuses a file with no start line', () => {
    expect(readJourneyLog(join(roundLine(1)), 'f').problem).toBe('noStart');
    expect(
      readJourneyLog(join({ event: 'start', tool: 'other', version: 3, t: T0 }, roundLine(1)), 'f')
        .problem,
    ).toBe('noStart');
  });

  it('refuses a start line without a good time', () => {
    expect(readJourneyLog(join({ ...START, t: 5 }, roundLine(1)), 'f').problem).toBe('noStart');
  });

  it.each([1, 4, '3', null])('refuses logger version %s', (version) => {
    expect(readJourneyLog(join({ ...START, version }, roundLine(1)), 'f').problem).toBe(
      'unknownVersion',
    );
  });

  it('refuses a file with no rounds, or only unusable ones', () => {
    expect(read().problem).toBe('noRounds');
    expect(read({ t: T0, round: 'x' }, { t: 1.5, round: 1 }).problem).toBe('noRounds');
    expect(read(roundLine(0)).problem).toBe('noRounds');
  });

  it('refuses rounds that do not rise, such as two runs in one file', () => {
    expect(read(roundLine(1), roundLine(2), roundLine(2)).problem).toBe('roundsNotRising');
    expect(read(roundLine(1), roundLine(2), roundLine(1)).problem).toBe('roundsNotRising');
  });

  it('refuses more than 2,000 rounds and takes exactly 2,000', () => {
    const rounds = (n) => Array.from({ length: n }, (_, i) => roundLine(i + 1));
    expect(read(...rounds(2001)).problem).toBe('tooManyRounds');
    expect(read(...rounds(2000)).summary.rounds).toBe(2000);
  });

  it('takes a request of exactly 4,000,000 characters and refuses one character more', () => {
    // M modems spread over 2,000 rounds. The search finds the most that is taken; the file name (sourceFile, up to
    // 200 characters) then pads the request's JSON to the exact length, since one modem adds about 180.
    const spread = (total, fileName = 'test.jsonl') =>
      readJourneyLog(
        join(
          START,
          ...Array.from({ length: 2000 }, (_, i) =>
            roundLine(
              i + 1,
              feedOf(
                Array.from(
                  { length: Math.floor(total / 2000) + (i < total % 2000 ? 1 : 0) },
                  (_, j) => link({ index: String(j) }),
                ),
              ),
            ),
          ),
        ),
        fileName,
      );
    let ok = 0;
    let refused = 32000;
    while (refused - ok > 1) {
      const middle = Math.floor((ok + refused) / 2);
      if (spread(middle).ok) ok = middle;
      else refused = middle;
    }
    const lengthOf = (result) => JSON.stringify(result.request).length;
    const gap = 4000000 - lengthOf(spread(ok));
    expect(gap).toBeGreaterThanOrEqual(0);
    expect(gap).toBeLessThan(190);
    expect(spread(refused)).toEqual({ ok: false, problem: 'tooBig' });

    const exact = spread(ok, 'a'.repeat(10 + gap));
    expect(exact.ok).toBe(true);
    expect(lengthOf(exact)).toBe(4000000);
    expect(spread(ok, 'a'.repeat(11 + gap))).toEqual({ ok: false, problem: 'tooBig' });
  }, 60000);

  it('refuses a request over 4,000,000 characters', () => {
    const fat = (n) =>
      roundLine(n, {
        feed: {
          connectivity: {
            links: Array.from({ length: 16 }, (_, i) =>
              link({
                index: String(i),
                device_type: 'T'.repeat(40),
                device_subtype: 'S'.repeat(40),
                device_state: 'D'.repeat(40),
                link_state: 'L'.repeat(40),
              }),
            ),
          },
        },
      });
    const result = read(...Array.from({ length: 2000 }, (_, i) => fat(i + 1)));
    expect(result).toEqual({ ok: false, problem: 'tooBig' });
  });
});

describe('never throwing', () => {
  it.each([
    '[]',
    'null',
    '5',
    '"text"',
    '{"event":"start"',
    '{"event":"start","tool":"train-feed-logger","version":3,"t":1790000000000,"device":7,"macos":{}}',
    '{"round":1,"t":1790000000001,"feed":[],"probe":5,"laptop":"x"}',
    '\u0000\u0001',
  ])('answers for %j', (text) => {
    const result = readJourneyLog(text, 5);
    expect(typeof result.ok).toBe('boolean');
  });

  it('copes with every part of a round being the wrong shape', () => {
    const result = read(
      roundLine(1, {
        feed: { position: 4, connectivity: { links: 'x' }, users: [], user: null, system: 1 },
      }),
    );
    expect(result.request.samples).toEqual([{ round: 1, at: T0 + 30000 }]);
  });
});

describe('kinds', () => {
  const lat = (value) => read(roundWith(['feed', 'position', 'latitude'], value));
  const sample = (result) => result.request.samples[0];

  it.each([
    ['12', 12],
    ['-3', -3],
    ['12.50', 12.5],
    ['-0', 0],
  ])('reads the feed text %j as a decimal', (text, expected) => {
    expect(sample(lat(text)).latitude).toBe(expected);
  });

  it.each([
    '1e3',
    ' 12',
    '12abc',
    '',
    '12.',
    '.5',
    '0x10',
    '1234567890123456',
    '1.1234567890123456',
    12,
    true,
    null,
    {},
    [],
  ])('does not read %j as a number', (value) => {
    expect(sample(lat(value)).latitude).toBeUndefined();
  });

  it('counts a bad number but not a missing or null one', () => {
    expect(leftOut(lat('12abc'))).toEqual({ latitude: 1 });
    expect(leftOut(lat(null))).toEqual({});
    expect(leftOut(read(roundLine(1)))).toEqual({});
  });

  it('reads whole numbers only as whole', () => {
    const online = (v) => sample(read(roundWith(['feed', 'connectivity', 'online'], v))).online;
    expect(online('3')).toBe(3);
    expect(online('3.0')).toBeUndefined();
    expect(online('-0')).toBe(0);
  });

  it('reads the logger numbers as numbers, whole where whole is wanted', () => {
    const battery = (v) => sample(read(roundWith(['laptop', 'battery'], v))).batteryPercent;
    expect(battery(50)).toBe(50);
    expect(battery(50.5)).toBeUndefined();
    expect(battery('50')).toBeUndefined();
    expect(battery(true)).toBeUndefined();
    expect(battery(Number.NaN)).toBeUndefined();
    const load = (v) => sample(read(roundWith(['laptop', 'load'], v))).loadAvg;
    expect(load(2.5)).toBe(2.5);
    expect(load('2.5')).toBeUndefined();
  });

  it('takes a text only when printable ASCII of the right length', () => {
    const unit = (v) => sample(read(roundWith(['feed', 'system', 'system_name'], v))).trainUnit;
    expect(unit('Unit 7 ~!')).toBe('Unit 7 ~!');
    expect(unit('U'.repeat(100))).toBe('U'.repeat(100));
    expect(unit('U'.repeat(101))).toBeUndefined();
    expect(unit('')).toBeUndefined();
    expect(unit('Unit\n7')).toBeUndefined();
    expect(unit('Unité')).toBeUndefined();
    expect(unit(12)).toBeUndefined();
    expect(unit(false)).toBeUndefined();
  });

  it('takes the file name only when it is a good text', () => {
    const name = (n) => readJourneyLog(join(START, roundLine(1)), n).request.sourceFile;
    expect(name('a.jsonl')).toBe('a.jsonl');
    expect(name('a'.repeat(200))).toHaveLength(200);
    expect(name('a'.repeat(201))).toBeUndefined();
    expect(name('')).toBeUndefined();
    expect(name(undefined)).toBeUndefined();
    expect(name('café.jsonl')).toBeUndefined();
  });

  it('takes the enumerated words only as written', () => {
    const power = (v) => sample(read(roundWith(['laptop', 'power'], v))).powerSource;
    expect(power('ac')).toBe('ac');
    expect(power('battery')).toBe('battery');
    expect(power('AC')).toBeUndefined();
    const cls = (v) =>
      readJourneyLog(join({ ...START, travel_class: v }, roundLine(1)), 'f').request.travelClass;
    expect(cls('standard')).toBe('standard');
    expect(cls('business')).toBeUndefined();
  });
});

describe("the feed's no value", () => {
  const modemOf = (extra) =>
    read(roundLine(1, feedOf([link(extra)]))).request.samples[0].modems?.[0];

  it.each([
    ['rssi', { rssi: '-1' }],
    ['technology', { technology: '-1' }],
    ['operatorId', { operator_id: '-1' }],
    ['cellId', { umts_info: { cellid: '-1' } }],
  ])('writes -1 in a modem %s as null and does not count it', (key, extra) => {
    const result = read(roundLine(1, feedOf([link(extra)])));
    const modem = result.request.samples[0].modems[0];
    expect(modem[key]).toBeNull();
    expect(Object.keys(modem)).toContain(key);
    expect(result.summary.leftOut).toEqual([]);
  });

  it('leaves out and counts what is neither good nor -1', () => {
    const result = read(
      roundLine(
        1,
        feedOf([link({ rssi: '-1.0', technology: '', operator_id: 'abc', umts_info: {} })]),
      ),
    );
    const modem = result.request.samples[0].modems[0];
    expect(modem).not.toHaveProperty('rssi');
    expect(modem).not.toHaveProperty('technology');
    expect(modem).not.toHaveProperty('operatorId');
    expect(modem).not.toHaveProperty('cellId');
    expect(leftOut(result)).toEqual({ rssi: 1, technology: 1, operatorId: 1 });
  });

  it('keeps a longitude of -1 as a place', () => {
    const result = read(roundWith(['feed', 'position', 'longitude'], '-1'));
    expect(result.request.samples[0].longitude).toBe(-1);
  });

  it('does not treat -1 as no value in the other modem fields', () => {
    expect(modemOf({ device_state: '-1' }).deviceState).toBe('-1');
    expect(modemOf({ index: '-1' })).toBeUndefined();
  });
});

// The contract's ranges, written out from the plan ("The import's request"), not from the module.
const CONTRACT = [
  // [name, path in the round, kind, lowest, highest]
  ['feedAt', ['feed', 'position', 'time'], 'int', 1577836800, 4102444800],
  ['fixMode', ['feed', 'position', 'mode'], 'int', 0, 9],
  ['fixAgeS', ['feed', 'position', 'age'], 'int', 0, 1000000],
  ['latitude', ['feed', 'position', 'latitude'], 'dec', -90, 90],
  ['longitude', ['feed', 'position', 'longitude'], 'dec', -180, 180],
  ['altitudeM', ['feed', 'position', 'altitude'], 'dec', -1000, 10000],
  ['speedMps', ['feed', 'position', 'speed'], 'dec', 0, 200],
  ['courseDeg', ['feed', 'position', 'cmg'], 'dec', 0, 360],
  ['satellites', ['feed', 'position', 'satellites'], 'int', 0, 200],
  ['online', ['feed', 'connectivity', 'online'], 'int', 0, 9],
  ['usersOnline', ['feed', 'users', 'online'], 'int', 0, 100000],
  ['usersTotal', ['feed', 'users', 'total'], 'int', 0, 100000],
  ['downLimit', ['feed', 'user', 'bandwidth_download_limit'], 'int', 0, 1e12],
  ['upLimit', ['feed', 'user', 'bandwidth_upload_limit'], 'int', 0, 1e12],
  ['dataDownUsed', ['feed', 'user', 'data_download_used'], 'int', 0, 1e15],
  ['dataUpUsed', ['feed', 'user', 'data_upload_used'], 'int', 0, 1e15],
  ['capLevel', ['feed', 'user', 'cap_level'], 'int', 0, 1000],
  ['userClass', ['feed', 'user', 'userclass'], 'int', 0, 1000],
  ['probeMs', ['probe', 'ms'], 'int', 0, 600000],
  ['probeCode', ['probe', 'code'], 'int', 100, 599],
  ['batteryPercent', ['laptop', 'battery'], 'int', 0, 100],
  ['lowPower', ['laptop', 'low_power'], 'int', 0, 1],
  ['cpuLimit', ['laptop', 'cpu_limit'], 'int', 0, 100],
  ['loadAvg', ['laptop', 'load'], 'dec', 0, 100000],
  ['swapMb', ['laptop', 'swap_mb'], 'dec', 0, 10000000],
  ['wifiRssi', ['laptop', 'wifi_rssi'], 'int', -120, -1],
  ['wifiNoise', ['laptop', 'wifi_noise'], 'int', -120, -1],
  ['wifiRate', ['laptop', 'wifi_rate'], 'dec', 0, 100000],
  ['wifiPhy', ['laptop', 'wifi_phy'], 'int', 1, 20],
  ['wifiBand', ['laptop', 'wifi_band'], 'int', 1, 20],
  ['wifiWidth', ['laptop', 'wifi_width'], 'int', 1, 20],
  ['wifiChannel', ['laptop', 'wifi_channel'], 'int', 1, 233],
];

describe('the ranges', () => {
  const value = (path, n) => (path[0] === 'feed' ? String(n) : n);
  const run = (name, path, n) => {
    const result = read(roundWith(path, value(path, n)));
    return { got: result.request.samples[0][name], counted: leftOut(result)[name] };
  };

  it.each(CONTRACT)(
    '%s keeps its lowest and highest value and refuses beyond',
    (name, path, kind, lo, hi) => {
      const step = kind === 'int' ? 1 : 0.5;
      const lowest = name === 'wifiRate' ? 0.5 : lo;
      // The feed's whole numbers stop at 15 digits, so the contract's 10^15 is reached one below.
      const highest = hi === 1e15 ? hi - 1 : hi;
      expect(run(name, path, lowest).got).toBe(lowest);
      expect(run(name, path, highest).got).toBe(highest);
      const below = run(name, path, name === 'wifiRate' ? 0 : lo - step);
      expect(below).toEqual({ got: undefined, counted: 1 });
      const above = run(name, path, hi + step);
      expect(above).toEqual({ got: undefined, counted: 1 });
    },
  );

  it('excludes zero for the Wi-Fi rate and takes a small positive one', () => {
    expect(run('wifiRate', ['laptop', 'wifi_rate'], 0).got).toBeUndefined();
    expect(run('wifiRate', ['laptop', 'wifi_rate'], 0.1).got).toBe(0.1);
    expect(run('wifiRate', ['laptop', 'wifi_rate'], 100000).got).toBe(100000);
    expect(run('wifiRate', ['laptop', 'wifi_rate'], 100000.1).got).toBeUndefined();
  });

  it('holds the module table to the contract', () => {
    expect(MAX_REQUEST_CHARS).toBe(4000000);
    for (const [name, , kind, lo, hi] of CONTRACT) {
      expect(RANGES[name]).toMatchObject({ kind, lo, hi });
    }
    expect(RANGES.wifiRate.lowOpen).toBe(true);
    expect(RANGES).toMatchObject({
      runStartedMs: { kind: 'int', lo: 1577836800000, hi: 4102444800000 },
      at: { kind: 'int', lo: 1577836800000, hi: 4102444800000 },
      round: { kind: 'int', lo: 1, hi: 10000000 },
      sourceFile: { kind: 'text', max: 200 },
      osVersion: { kind: 'text', max: 40 },
      trainUnit: { kind: 'text', max: 100 },
      travelClass: { kind: 'enum', values: ['standard', 'first'] },
      powerSource: { kind: 'enum', values: ['ac', 'battery'] },
      deviceModel: { kind: 'text', max: 100 },
      deviceCpu: { kind: 'text', max: 100 },
      deviceCores: { kind: 'int', lo: 1, hi: 1024 },
      deviceThreads: { kind: 'int', lo: 1, hi: 4096 },
      deviceMemoryBytes: { kind: 'int', lo: 1, hi: 1125899906842624 },
      modemIndex: { kind: 'int', lo: 0, hi: 100000 },
      deviceType: { kind: 'text', max: 40 },
      deviceSubtype: { kind: 'text', max: 40 },
      deviceState: { kind: 'text', max: 40 },
      linkState: { kind: 'text', max: 40 },
      technology: { kind: 'text', max: 40 },
      rssi: { kind: 'int', lo: -200, hi: 0 },
      operatorId: { kind: 'digits', max: 10 },
      cellId: { kind: 'hex', max: 10 },
    });
  });

  it('holds the run start, the round and the sample time to their ranges', () => {
    const start = (t) => readJourneyLog(join({ ...START, t }, roundLine(1)), 'f');
    expect(start(1577836800000).ok).toBe(true);
    expect(start(1577836799999).problem).toBe('noStart');
    expect(start(4102444800000).ok).toBe(true);
    expect(start(4102444800001).problem).toBe('noStart');
    const rounds = (round, t) => read({ round, t });
    expect(rounds(10000000, T0).ok).toBe(true);
    expect(rounds(10000001, T0).problem).toBe('noRounds');
    expect(rounds(1, 1577836799999).problem).toBe('noRounds');
    expect(leftOut(read(roundLine(1), { round: 2, t: 1 }, roundLine(3)))).toEqual({ samples: 1 });
  });

  it.each(['12G4', '0x1E240', '-1E240', '1E 240', '', 123456])(
    'leaves out and counts the cell id %j',
    (cellid) => {
      const result = read(roundLine(1, feedOf([link({ umts_info: { cellid } })])));
      expect(result.request.samples[0].modems[0]).not.toHaveProperty('cellId');
      expect(leftOut(result)).toEqual({ cellId: 1 });
    },
  );

  it('holds the modem fields to their ranges', () => {
    const modem = (extra) =>
      read(roundLine(1, feedOf([link(extra)]))).request.samples[0].modems?.[0];
    expect(modem({ index: '0' }).index).toBe(0);
    expect(modem({ index: '100000' }).index).toBe(100000);
    expect(modem({ index: '100001' })).toBeUndefined();
    expect(modem({ rssi: '0' }).rssi).toBe(0);
    expect(modem({ rssi: '-200' }).rssi).toBe(-200);
    expect(modem({ rssi: '1' })).not.toHaveProperty('rssi');
    expect(modem({ rssi: '-201' })).not.toHaveProperty('rssi');
    expect(modem({ umts_info: { cellid: '0' } }).cellId).toBe(0);
    expect(modem({ umts_info: { cellid: '1e240' } }).cellId).toBe(123456);
    expect(modem({ umts_info: { cellid: '1E240' } }).cellId).toBe(123456);
    expect(modem({ umts_info: { cellid: '01234567' } }).cellId).toBe(19088743);
    expect(modem({ umts_info: { cellid: 'FFFFFFFFFF' } }).cellId).toBe(1099511627775);
    expect(modem({ umts_info: { cellid: '10000000000' } })).not.toHaveProperty('cellId');
    expect(modem({ operator_id: '1234567890' }).operatorId).toBe('1234567890');
    expect(modem({ operator_id: '12345678901' })).not.toHaveProperty('operatorId');
    expect(modem({ operator_id: '12a' })).not.toHaveProperty('operatorId');
    expect(modem({ device_type: 'T'.repeat(40) }).deviceType).toHaveLength(40);
    expect(modem({ device_type: 'T'.repeat(41) })).not.toHaveProperty('deviceType');
  });
});

describe('the device', () => {
  const device = (patch) =>
    readJourneyLog(join({ ...START, device: { ...START.device, ...patch } }, roundLine(1)), 'f');

  it('is sent whole when all five columns are good', () => {
    expect(device({}).request.device).toEqual({
      model: 'Testbook1,1',
      cpu: 'Test Chip One',
      cores: 8,
      threads: 8,
      memoryBytes: 17179869184,
    });
  });

  it.each([
    { model: null },
    { cpu: 'C'.repeat(101) },
    { cores: 0 },
    { threads: 4097 },
    { memory_bytes: 2 ** 50 + 2 },
  ])('is left out whole, and counted once, when one column is bad: %j', (patch) => {
    const result = device(patch);
    expect(result.request).not.toHaveProperty('device');
    expect(result.summary.hasDevice).toBe(false);
    expect(leftOut(result)).toEqual({ device: 1 });
  });

  it('takes the upper bounds of the five columns', () => {
    const result = device({
      model: 'm'.repeat(100),
      cpu: 'c'.repeat(100),
      cores: 1024,
      threads: 4096,
      memory_bytes: 2 ** 50,
    });
    expect(result.summary.hasDevice).toBe(true);
  });
});

describe('what a file can be missing or carry badly', () => {
  it('still makes a sample of a round with no feed, from its probe and laptop', () => {
    const result = read(
      roundLine(1, { probe: { ms: 12, code: 204 }, laptop: { power: 'ac', load: 1.5 } }),
    );
    expect(result.request.samples).toEqual([
      { round: 1, at: T0 + 30000, probeMs: 12, probeCode: 204, powerSource: 'ac', loadAvg: 1.5 },
    ]);
  });

  it('skips and counts a last line cut off by a run that died', () => {
    const result = readJourneyLog(
      `${join(START, roundLine(1), roundLine(2))}\n{"t": 17900000`,
      'f',
    );
    expect(result.ok).toBe(true);
    expect(result.summary).toMatchObject({ rounds: 2, unreadableLines: 1 });
  });

  it('counts every line that does not parse, and reads lines ended by CR LF', () => {
    const text = [
      JSON.stringify(START),
      'oops',
      '',
      JSON.stringify(roundLine(1)),
      '{',
      JSON.stringify(roundLine(2)),
    ].join('\r\n');
    expect(readJourneyLog(text, 'f').summary).toMatchObject({ rounds: 2, unreadableLines: 2 });
  });

  it('leaves out a link with no good index, a repeated one, and counts them', () => {
    const result = read(
      roundLine(
        1,
        feedOf([
          link({ index: '5' }),
          link({ index: undefined }),
          link({ index: 'x' }),
          link({ index: '5' }),
          'link',
          link({ index: '6' }),
        ]),
      ),
    );
    expect(result.request.samples[0].modems.map((m) => m.index)).toEqual([5, 6]);
    expect(leftOut(result)).toEqual({ modems: 4 });
    expect(result.summary.modems).toEqual([5, 6]);
  });

  it('keeps sixteen links and counts the seventeenth', () => {
    const links = Array.from({ length: 17 }, (_, i) => link({ index: String(i) }));
    const result = read(roundLine(1, feedOf(links)));
    expect(result.request.samples[0].modems).toHaveLength(16);
    expect(leftOut(result)).toEqual({ modems: 1 });
  });

  it('sends no modems when the feed has no links, or only unusable ones', () => {
    expect(read(roundLine(1, feedOf([]))).request.samples[0]).not.toHaveProperty('modems');
    expect(read(roundLine(1, feedOf([{}]))).request.samples[0]).not.toHaveProperty('modems');
  });

  it('lists modems rising and units in order of first appearance, most-left-out first', () => {
    const result = read(
      roundLine(1, {
        feed: {
          system: { system_name: 'B' },
          position: { latitude: 'x', longitude: 'x', speed: 'x' },
          connectivity: { links: [link({ index: '9' })] },
        },
      }),
      roundLine(2, {
        feed: {
          system: { system_name: 'A' },
          position: { latitude: 'x' },
          connectivity: { links: [link({ index: '3' })] },
        },
      }),
      roundLine(3, { feed: { system: { system_name: 'B' } } }),
    );
    expect(result.summary.units).toEqual(['B', 'A']);
    expect(result.summary.modems).toEqual([3, 9]);
    expect(result.summary.leftOut).toEqual([
      { field: 'latitude', count: 2 },
      { field: 'longitude', count: 1 },
      { field: 'speedMps', count: 1 },
    ]);
  });
});
