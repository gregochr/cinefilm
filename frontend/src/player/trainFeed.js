// Reads the train's position answer (decision 1 of the train look-ahead plan). Pure: text in, a fix or null out.
// The answer is JSONP ("cb({...});") with trailing commas in places, and its numbers are written as text.
// Nothing here keeps, sends or stores a position.

/** The train's own position answer (it resolves only on the train); `trainFeedFrame.js` asks for it. */
export const TRAIN_FEED_URL = 'https://www.ombord.info/api/jsonp/position/';

const MAX_TEXT = 100_000;
// Seconds since 1970: 2020-01-01 to 2100-01-01, the range the journey file's own `feedAt` uses.
const TIME_LOW = 1_577_836_800;
const TIME_HIGH = 4_102_444_800;

const WRAPPED = /^[\w$.]*\s*\(([\s\S]*)\)\s*;?$/;
const WHOLE = /^-?\d{1,15}$/;
const DECIMAL = /^-?\d{1,15}(\.\d{1,15})?$/;

/** Drops each comma that is followed (past spaces) by a closing bracket, leaving strings alone. */
function withoutTrailingCommas(text) {
  let out = '';
  let inString = false;
  for (let i = 0; i < text.length; i += 1) {
    const c = text[i];
    if (inString) {
      out += c;
      if (c === '\\') {
        i += 1;
        out += text[i] ?? '';
      } else if (c === '"') inString = false;
    } else if (c === '"') {
      inString = true;
      out += c;
    } else if (c === ',') {
      let j = i + 1;
      while (j < text.length && /\s/.test(text[j])) j += 1;
      if (text[j] !== '}' && text[j] !== ']') out += c;
    } else out += c;
  }
  return out;
}

/** The answer's data as a plain object, or null. */
export function parseAnswer(text) {
  if (typeof text !== 'string') return null;
  const trimmed = text.trim();
  if (trimmed.length === 0 || trimmed.length > MAX_TEXT) return null;
  const wrapped = WRAPPED.exec(trimmed);
  try {
    const data = JSON.parse(withoutTrailingCommas(wrapped ? wrapped[1] : trimmed));
    return data !== null && typeof data === 'object' && !Array.isArray(data) ? data : null;
  } catch {
    return null;
  }
}

/** A number written as text (or a finite number) inside [lo, hi]; undefined when it is anything else. */
function read(raw, { whole = false, lo, hi }) {
  let n;
  if (typeof raw === 'string') {
    if (!(whole ? WHOLE : DECIMAL).test(raw)) return undefined;
    n = Number(raw);
  } else if (typeof raw === 'number' && Number.isFinite(raw)) {
    if (whole && !Number.isInteger(raw)) return undefined;
    n = raw;
  } else return undefined;
  return n >= lo && n <= hi ? n : undefined;
}

/**
 * The position answer as a fix `{ latitude, longitude, courseDeg, speedMps, ageS, feedAtS }` (degrees, degrees
 * clockwise from north in [0, 360), metres per second, seconds, seconds since 1970), or null when any of
 * latitude, longitude, cmg (course), speed, age, time or mode is missing, of the wrong kind, or out of range.
 * The mode is checked but not used: about a fifth of real rounds say mode 0 with fresh, moving positions
 * (`journey-data-plan.md`), so what a mode is worth is not decided here. `ageS` is the fix's own age as the feed
 * states it; whether it is too old is the matcher's decision.
 */
export function parseFix(text) {
  const data = parseAnswer(text);
  if (data === null) return null;
  const latitude = read(data.latitude, { lo: -90, hi: 90 });
  const longitude = read(data.longitude, { lo: -180, hi: 180 });
  const course = read(data.cmg, { lo: 0, hi: 360 });
  const speedMps = read(data.speed, { lo: 0, hi: 200 });
  const ageS = read(data.age, { lo: 0, hi: 1_000_000 });
  const feedAtS = read(data.time, { whole: true, lo: TIME_LOW, hi: TIME_HIGH });
  const mode = read(data.mode, { whole: true, lo: 0, hi: 9 });
  const values = [latitude, longitude, course, speedMps, ageS, feedAtS, mode];
  if (values.includes(undefined)) return null;
  return { latitude, longitude, courseDeg: course % 360, speedMps, ageS, feedAtS };
}
