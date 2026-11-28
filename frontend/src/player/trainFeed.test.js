import { describe, expect, it } from 'vitest';
import { parseAnswer, parseFix } from './trainFeed.js';

// Made-up values; nothing here is a real place.
const GOOD = {
  version: '1.6',
  time: '1800000000',
  age: '2',
  latitude: '0.500000',
  longitude: '-20.000000',
  altitude: '12.0',
  speed: '30.5',
  cmg: '90.5',
  satellites: '9',
  mode: '3',
};
const jsonp = (data) => `cb(${JSON.stringify(data)});`;
const without = (key) => {
  const copy = { ...GOOD };
  delete copy[key];
  return copy;
};

describe('parseAnswer', () => {
  it('reads plain JSON', () => {
    expect(parseAnswer('{"a":"1"}')).toEqual({ a: '1' });
  });

  it('reads JSONP with a named callback, an anonymous one, and with or without the semicolon', () => {
    expect(parseAnswer('cb({"a":"1"});')).toEqual({ a: '1' });
    expect(parseAnswer('window.cb_1 ( {"a":"1"} )')).toEqual({ a: '1' });
    expect(parseAnswer('( {"a":"1"} );')).toEqual({ a: '1' });
  });

  it('drops trailing commas in objects and arrays, even with spaces and new lines before the bracket', () => {
    expect(parseAnswer('cb({"a":"1","b":["x","y",\n],  });')).toEqual({ a: '1', b: ['x', 'y'] });
  });

  it('leaves commas and brackets inside strings alone, escaped quotes included', () => {
    expect(parseAnswer('{"a":"x,}","b":"q\\",]"}')).toEqual({ a: 'x,}', b: 'q",]' });
  });

  it.each([
    ['not a string', 42],
    ['null', null],
    ['empty', ''],
    ['blank', '   '],
    ['not JSON', 'cb(nope);'],
    ['a sign-in page', '<html><body>Sign in</body></html>'],
    ['an array', '[1,2]'],
    ['a bare number', '5'],
    ['a JSON null', 'null'],
    ['a string cut off after a backslash', '{"a":"\\'],
  ])('refuses %s', (_name, input) => {
    expect(parseAnswer(input)).toBeNull();
  });

  it('refuses text over 100,000 characters and accepts one at 100,000', () => {
    const pad = (n) => `{"a":"${'x'.repeat(n - 8)}"}`;
    expect(pad(100_000)).toHaveLength(100_000);
    expect(parseAnswer(pad(100_000))).not.toBeNull();
    expect(parseAnswer(pad(100_001))).toBeNull();
  });
});

describe('parseFix', () => {
  it('turns a good answer into a normalised fix', () => {
    expect(parseFix(jsonp(GOOD))).toEqual({
      latitude: 0.5,
      longitude: -20,
      courseDeg: 90.5,
      speedMps: 30.5,
      ageS: 2,
      feedAtS: 1_800_000_000,
    });
  });

  it('reads the answer the logger sees: trailing commas and all', () => {
    const text = `cb({"time":"1800000000","age":"0","latitude":"0.5","longitude":"-20","speed":"1","cmg":"0","mode":"2",});`;
    expect(parseFix(text)).toMatchObject({ latitude: 0.5, longitude: -20, ageS: 0 });
  });

  it('accepts real numbers as well as numbers written as text', () => {
    const fix = parseFix(
      jsonp({ ...GOOD, latitude: 0.5, speed: 30, age: 0, time: 1_800_000_000, mode: 3 }),
    );
    expect(fix).toMatchObject({ latitude: 0.5, speedMps: 30, ageS: 0, feedAtS: 1_800_000_000 });
  });

  it.each(['latitude', 'longitude', 'cmg', 'speed', 'age', 'time', 'mode'])(
    'refuses an answer with no %s',
    (key) => {
      expect(parseFix(jsonp(without(key)))).toBeNull();
    },
  );

  it.each([
    ['text with a unit', '5 m/s'],
    ['an escape sequence', '\u001b[31m5'],
    ['an empty string', ''],
    ['an exponent', '1e2'],
    ['sixteen digits', '1234567890123456'],
    ['null', null],
    ['a boolean', true],
    ['an object', {}],
    ['NaN written as text', 'NaN'],
  ])('refuses %s in a number field', (_name, bad) => {
    for (const key of ['latitude', 'longitude', 'cmg', 'speed', 'age', 'time', 'mode']) {
      expect(parseFix(jsonp({ ...GOOD, [key]: bad }))).toBeNull();
    }
  });

  it('refuses a decimal where a whole number belongs, as text or as a number', () => {
    expect(parseFix(jsonp({ ...GOOD, time: '1800000000.5' }))).toBeNull();
    expect(parseFix(jsonp({ ...GOOD, time: 1_800_000_000.5 }))).toBeNull();
    expect(parseFix(jsonp({ ...GOOD, mode: '2.5' }))).toBeNull();
    expect(parseFix(jsonp({ ...GOOD, mode: 2.5 }))).toBeNull();
  });

  it('refuses a number that is not finite', () => {
    // JSON cannot carry one, so reach the guard through a parsed object.
    expect(parseFix('{"latitude":1e999}')).toBeNull();
  });

  describe('ranges, at and just beyond each end', () => {
    const accepts = (key, value) => parseFix(jsonp({ ...GOOD, [key]: value })) !== null;

    it('latitude -90 to 90', () => {
      expect(accepts('latitude', '90')).toBe(true);
      expect(accepts('latitude', '-90')).toBe(true);
      expect(accepts('latitude', '90.000001')).toBe(false);
      expect(accepts('latitude', '-90.000001')).toBe(false);
    });

    it('longitude -180 to 180', () => {
      expect(accepts('longitude', '180')).toBe(true);
      expect(accepts('longitude', '-180')).toBe(true);
      expect(accepts('longitude', '180.000001')).toBe(false);
      expect(accepts('longitude', '-180.000001')).toBe(false);
    });

    it('course 0 to 360, with 360 read as 0', () => {
      expect(accepts('cmg', '0')).toBe(true);
      expect(parseFix(jsonp({ ...GOOD, cmg: '360' })).courseDeg).toBe(0);
      expect(accepts('cmg', '360.1')).toBe(false);
      expect(accepts('cmg', '-0.1')).toBe(false);
    });

    it('speed 0 to 200 m/s', () => {
      expect(accepts('speed', '0')).toBe(true);
      expect(accepts('speed', '200')).toBe(true);
      expect(accepts('speed', '200.1')).toBe(false);
      expect(accepts('speed', '-0.1')).toBe(false);
    });

    it('age 0 to 1,000,000 s', () => {
      expect(accepts('age', '0')).toBe(true);
      expect(accepts('age', '1000000')).toBe(true);
      expect(accepts('age', '1000001')).toBe(false);
      expect(accepts('age', '-1')).toBe(false);
    });

    it('the fix time from 2020-01-01 to 2100-01-01', () => {
      expect(accepts('time', '1577836800')).toBe(true);
      expect(accepts('time', '1577836799')).toBe(false);
      expect(accepts('time', '4102444800')).toBe(true);
      expect(accepts('time', '4102444801')).toBe(false);
    });

    it('mode 0 to 9', () => {
      expect(accepts('mode', '0')).toBe(true);
      expect(accepts('mode', '9')).toBe(true);
      expect(accepts('mode', '-1')).toBe(false);
      expect(accepts('mode', '10')).toBe(false);
    });
  });

  it('accepts a mode 0 fix with a fresh age: real moving positions arrive so', () => {
    expect(parseFix(jsonp({ ...GOOD, mode: '0', age: '0' }))).toMatchObject({
      latitude: 0.5,
      ageS: 0,
    });
  });

  it('refuses text that is not an object answer', () => {
    expect(parseFix('')).toBeNull();
    expect(parseFix('[]')).toBeNull();
    expect(parseFix(undefined)).toBeNull();
  });

  it('does not judge age against any limit: that is the matcher’s', () => {
    expect(parseFix(jsonp({ ...GOOD, age: '999' })).ageS).toBe(999);
  });
});
