/**
 * Reads the text of one train feed logger file (`scripts/train-feed-logger.py`, versions 2 and 3) into the request
 * of `POST /api/admin/journeys`, and says what it found. Pure: no DOM, no fetch, and it never throws.
 *
 * Only the named columns leave the file. The laptop's addresses, the feed's addresses, the Wi-Fi network's name and
 * everything else the logger writes are never copied. Contract: `docs/engineering/journey-data-plan.md`, "The
 * import's request".
 */

const MS_LOW = Date.UTC(2020, 0, 1);
const MS_HIGH = Date.UTC(2100, 0, 1);

export const MAX_ROUNDS = 2000;
export const MAX_MODEMS = 16;
export const MAX_REQUEST_CHARS = 4_000_000;

/** The contract's ranges, one row per field. `max` is a text's greatest length (a hex text's greatest digit count); `lowOpen` excludes `lo`. */
export const RANGES = {
  runStartedMs: { kind: 'int', lo: MS_LOW, hi: MS_HIGH },
  sourceFile: { kind: 'text', max: 200 },
  osVersion: { kind: 'text', max: 40 },
  travelClass: { kind: 'enum', values: ['standard', 'first'] },
  deviceModel: { kind: 'text', max: 100 },
  deviceCpu: { kind: 'text', max: 100 },
  deviceCores: { kind: 'int', lo: 1, hi: 1024 },
  deviceThreads: { kind: 'int', lo: 1, hi: 4096 },
  deviceMemoryBytes: { kind: 'int', lo: 1, hi: 2 ** 50 },
  round: { kind: 'int', lo: 1, hi: 10_000_000 },
  at: { kind: 'int', lo: MS_LOW, hi: MS_HIGH },
  trainUnit: { kind: 'text', max: 100 },
  feedAt: { kind: 'int', lo: MS_LOW / 1000, hi: MS_HIGH / 1000 },
  fixMode: { kind: 'int', lo: 0, hi: 9 },
  fixAgeS: { kind: 'int', lo: 0, hi: 1_000_000 },
  latitude: { kind: 'dec', lo: -90, hi: 90 },
  longitude: { kind: 'dec', lo: -180, hi: 180 },
  altitudeM: { kind: 'dec', lo: -1000, hi: 10_000 },
  speedMps: { kind: 'dec', lo: 0, hi: 200 },
  courseDeg: { kind: 'dec', lo: 0, hi: 360 },
  satellites: { kind: 'int', lo: 0, hi: 200 },
  online: { kind: 'int', lo: 0, hi: 9 },
  usersOnline: { kind: 'int', lo: 0, hi: 100_000 },
  usersTotal: { kind: 'int', lo: 0, hi: 100_000 },
  downLimit: { kind: 'int', lo: 0, hi: 1e12 },
  upLimit: { kind: 'int', lo: 0, hi: 1e12 },
  dataDownUsed: { kind: 'int', lo: 0, hi: 1e15 },
  dataUpUsed: { kind: 'int', lo: 0, hi: 1e15 },
  capLevel: { kind: 'int', lo: 0, hi: 1000 },
  userClass: { kind: 'int', lo: 0, hi: 1000 },
  probeMs: { kind: 'int', lo: 0, hi: 600_000 },
  probeCode: { kind: 'int', lo: 100, hi: 599 },
  powerSource: { kind: 'enum', values: ['ac', 'battery'] },
  batteryPercent: { kind: 'int', lo: 0, hi: 100 },
  lowPower: { kind: 'int', lo: 0, hi: 1 },
  cpuLimit: { kind: 'int', lo: 0, hi: 100 },
  loadAvg: { kind: 'dec', lo: 0, hi: 100_000 },
  swapMb: { kind: 'dec', lo: 0, hi: 10_000_000 },
  wifiRssi: { kind: 'int', lo: -120, hi: -1 },
  wifiNoise: { kind: 'int', lo: -120, hi: -1 },
  wifiRate: { kind: 'dec', lo: 0, hi: 100_000, lowOpen: true },
  wifiPhy: { kind: 'int', lo: 1, hi: 20 },
  wifiBand: { kind: 'int', lo: 1, hi: 20 },
  wifiWidth: { kind: 'int', lo: 1, hi: 20 },
  wifiChannel: { kind: 'int', lo: 1, hi: 233 },
  modemIndex: { kind: 'int', lo: 0, hi: 100_000 },
  deviceType: { kind: 'text', max: 40 },
  deviceSubtype: { kind: 'text', max: 40 },
  deviceState: { kind: 'text', max: 40 },
  linkState: { kind: 'text', max: 40 },
  technology: { kind: 'text', max: 40 },
  rssi: { kind: 'int', lo: -200, hi: 0 },
  operatorId: { kind: 'digits', max: 10 },
  cellId: { kind: 'hex', max: 10 },
};

const FEED_WHOLE = /^-?\d{1,15}$/;
const FEED_DECIMAL = /^-?\d{1,15}(\.\d{1,15})?$/;
const PRINTABLE = /^[ -~]+$/;
const DIGITS = /^\d+$/;
const HEX = /^[0-9a-fA-F]+$/;

const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const isFiniteNumber = (v) => typeof v === 'number' && Number.isFinite(v);

/** The value when it is good for `name`, otherwise undefined. `fromFeed` values are text; the logger's are numbers. */
function good(name, raw, fromFeed) {
  const r = RANGES[name];
  if (r.kind === 'enum') return r.values.includes(raw) ? raw : undefined;
  if (r.kind === 'text' || r.kind === 'digits') {
    if (typeof raw !== 'string' || raw.length > r.max || !PRINTABLE.test(raw)) return undefined;
    return r.kind === 'digits' && !DIGITS.test(raw) ? undefined : raw;
  }
  if (r.kind === 'hex') {
    return typeof raw === 'string' && raw.length <= r.max && HEX.test(raw)
      ? parseInt(raw, 16)
      : undefined;
  }
  let n;
  if (fromFeed) {
    const pattern = r.kind === 'int' ? FEED_WHOLE : FEED_DECIMAL;
    if (typeof raw !== 'string' || !pattern.test(raw)) return undefined;
    n = Number(raw) + 0;
  } else {
    if (!isFiniteNumber(raw) || (r.kind === 'int' && !Number.isInteger(raw))) return undefined;
    n = raw + 0;
  }
  if (n < r.lo || n > r.hi || (r.lowOpen && n === r.lo)) return undefined;
  return n;
}

/** Keeps what the request is built from, and what was left out. */
function makeReader() {
  const leftOut = new Map();
  const count = (field) => leftOut.set(field, (leftOut.get(field) ?? 0) + 1);
  /** Copies `raw` into `target[key]` when good; a present value that is not good is counted. Missing and null are the same. */
  const put = (target, key, name, raw, fromFeed) => {
    if (raw === undefined || raw === null) return;
    const v = good(name, raw, fromFeed);
    if (v === undefined) count(key);
    else target[key] = v;
  };
  return { put, count, leftOut };
}

function readDevice(device, reader) {
  if (!isObject(device)) return undefined;
  const parts = {
    model: good('deviceModel', device.model, false),
    cpu: good('deviceCpu', device.cpu, false),
    cores: good('deviceCores', device.cores, false),
    threads: good('deviceThreads', device.threads, false),
    memoryBytes: good('deviceMemoryBytes', device.memory_bytes, false),
  };
  if (Object.values(parts).some((v) => v === undefined)) {
    reader.count('device');
    return undefined;
  }
  return parts;
}

/** The feed's "no value" (the exact text -1) in these four modem fields is written as null, and not counted. */
function putModemField(modem, key, raw, reader) {
  if (raw === '-1') modem[key] = null;
  else reader.put(modem, key, key, raw, true);
}

function readModems(links, reader) {
  if (!Array.isArray(links)) return undefined;
  const modems = [];
  const seen = new Set();
  for (const link of links) {
    const index = isObject(link) ? good('modemIndex', link.index, true) : undefined;
    if (index === undefined || seen.has(index) || modems.length >= MAX_MODEMS) {
      reader.count('modems');
      continue;
    }
    seen.add(index);
    const modem = { index };
    reader.put(modem, 'deviceType', 'deviceType', link.device_type, true);
    reader.put(modem, 'deviceSubtype', 'deviceSubtype', link.device_subtype, true);
    reader.put(modem, 'deviceState', 'deviceState', link.device_state, true);
    reader.put(modem, 'linkState', 'linkState', link.link_state, true);
    putModemField(modem, 'technology', link.technology, reader);
    putModemField(modem, 'rssi', link.rssi, reader);
    putModemField(modem, 'operatorId', link.operator_id, reader);
    putModemField(
      modem,
      'cellId',
      isObject(link.umts_info) ? link.umts_info.cellid : undefined,
      reader,
    );
    modems.push(modem);
  }
  return modems.length > 0 ? modems : undefined;
}

const section = (v) => (isObject(v) ? v : {});

// [request key, the source's key]
const POSITION = [
  ['feedAt', 'time'],
  ['fixMode', 'mode'],
  ['fixAgeS', 'age'],
  ['latitude', 'latitude'],
  ['longitude', 'longitude'],
  ['altitudeM', 'altitude'],
  ['speedMps', 'speed'],
  ['courseDeg', 'cmg'],
  ['satellites', 'satellites'],
];
const USER = [
  ['downLimit', 'bandwidth_download_limit'],
  ['upLimit', 'bandwidth_upload_limit'],
  ['capLevel', 'cap_level'],
  ['userClass', 'userclass'],
  ['dataDownUsed', 'data_download_used'],
  ['dataUpUsed', 'data_upload_used'],
];
const LAPTOP = [
  ['powerSource', 'power'],
  ['batteryPercent', 'battery'],
  ['lowPower', 'low_power'],
  ['cpuLimit', 'cpu_limit'],
  ['loadAvg', 'load'],
  ['swapMb', 'swap_mb'],
  ['wifiRssi', 'wifi_rssi'],
  ['wifiNoise', 'wifi_noise'],
  ['wifiRate', 'wifi_rate'],
  ['wifiPhy', 'wifi_phy'],
  ['wifiBand', 'wifi_band'],
  ['wifiWidth', 'wifi_width'],
  ['wifiChannel', 'wifi_channel'],
];

function readFeed(feed, sample, reader) {
  reader.put(sample, 'trainUnit', 'trainUnit', section(feed.system).system_name, true);
  const position = section(feed.position);
  for (const [key, source] of POSITION) reader.put(sample, key, key, position[source], true);
  const connectivity = section(feed.connectivity);
  reader.put(sample, 'online', 'online', connectivity.online, true);
  const users = section(feed.users);
  reader.put(sample, 'usersOnline', 'usersOnline', users.online, true);
  reader.put(sample, 'usersTotal', 'usersTotal', users.total, true);
  const user = section(feed.user);
  for (const [key, source] of USER) reader.put(sample, key, key, user[source], true);
  const modems = readModems(connectivity.links, reader);
  if (modems) sample.modems = modems;
}

function readSample(line, reader) {
  const round = good('round', line.round, false);
  const at = good('at', line.t, false);
  if (round === undefined || at === undefined) {
    reader.count('samples');
    return undefined;
  }
  const sample = { round, at };
  if (isObject(line.feed)) readFeed(line.feed, sample, reader);
  const probe = section(line.probe);
  reader.put(sample, 'probeMs', 'probeMs', probe.ms, false);
  reader.put(sample, 'probeCode', 'probeCode', probe.code, false);
  const laptop = section(line.laptop);
  for (const [key, source] of LAPTOP) reader.put(sample, key, key, laptop[source], false);
  return sample;
}

function parseLines(text) {
  const objects = [];
  let parsed = 0;
  let unreadable = 0;
  for (const line of text.split(/\r\n|\r|\n/)) {
    if (line.trim() === '') continue;
    try {
      const value = JSON.parse(line);
      parsed += 1;
      if (isObject(value)) objects.push(value);
    } catch {
      unreadable += 1;
    }
  }
  return { objects, parsed, unreadable };
}

const refuse = (problem) => ({ ok: false, problem });

function summarise(request, samples, reader, unreadableLines) {
  const units = [];
  const indexes = new Set();
  for (const s of samples) {
    if (s.trainUnit !== undefined && !units.includes(s.trainUnit)) units.push(s.trainUnit);
    for (const m of s.modems ?? []) indexes.add(m.index);
  }
  return {
    rounds: samples.length,
    from: samples[0].at,
    to: samples[samples.length - 1].at,
    units,
    modems: [...indexes].sort((a, b) => a - b),
    loggerVersion: request.loggerVersion,
    hasDevice: request.device !== undefined,
    travelClass: request.travelClass ?? null,
    unreadableLines,
    leftOut: [...reader.leftOut]
      .map(([field, count]) => ({ field, count }))
      .sort((a, b) => b.count - a.count || (a.field < b.field ? -1 : 1)),
  };
}

/**
 * The request for one logger file's text and the summary the page shows before sending it, or `{ ok: false,
 * problem }` with `problem` one of `empty`, `noStart`, `unknownVersion`, `noRounds`, `roundsNotRising`,
 * `tooManyRounds` and `tooBig`.
 */
export function readJourneyLog(text, fileName) {
  if (typeof text !== 'string') return refuse('empty');
  const { objects, parsed, unreadable } = parseLines(text);
  if (parsed === 0) return refuse('empty');
  const start = objects.find((o) => o.event === 'start' && o.tool === 'train-feed-logger');
  const runStartedMs = start ? good('runStartedMs', start.t, false) : undefined;
  if (runStartedMs === undefined) return refuse('noStart');
  if (start.version !== 2 && start.version !== 3) return refuse('unknownVersion');

  const reader = makeReader();
  const rounds = objects.filter((o) => Number.isInteger(o.round) && Number.isInteger(o.t));
  const samples = rounds.map((line) => readSample(line, reader)).filter((s) => s !== undefined);
  if (samples.length === 0) return refuse('noRounds');
  if (samples.some((s, i) => i > 0 && s.round <= samples[i - 1].round))
    return refuse('roundsNotRising');
  if (samples.length > MAX_ROUNDS) return refuse('tooManyRounds');

  const request = { runStartedMs, loggerVersion: start.version };
  reader.put(request, 'sourceFile', 'sourceFile', fileName, false);
  reader.put(request, 'osVersion', 'osVersion', start.macos, false);
  reader.put(request, 'travelClass', 'travelClass', start.travel_class, false);
  const device = readDevice(start.device, reader);
  if (device) request.device = device;
  request.samples = samples;

  if (JSON.stringify(request).length > MAX_REQUEST_CHARS) return refuse('tooBig');
  return { ok: true, request, summary: summarise(request, samples, reader, unreadable) };
}
