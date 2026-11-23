import { describe, expect, it } from 'vitest';
import { readRepoJson } from '../test/repoFile.js';
import { MAX_TRAIN_FILE_CHARS, readTrainFile } from './trainFile.js';

const EXAMPLE = readRepoJson('backend/src/test/resources/journey/train-file-example.json');
const call = (location) => ({ location });
const file = (over = {}) => ({
  uid: 'X12345',
  date: '2030-03-04',
  headcode: '9Z99',
  calls: [call('Alpha Junction'), call('Theta Central')],
  ...over,
});
const read = (value) => readTrainFile(JSON.stringify(value));

describe('readTrainFile', () => {
  it('reads the backend’s worked example into the same request and its summary', () => {
    const result = readTrainFile(JSON.stringify(EXAMPLE));

    expect(result).toEqual({
      ok: true,
      request: EXAMPLE,
      summary: {
        uid: 'X12345',
        date: '2030-03-04',
        headcode: '9Z99',
        calls: 8,
        from: 'Alpha Junction',
        to: 'Theta Central',
      },
    });
  });

  it('passes a name it does not know through untouched', () => {
    const extra = file({ somethingElse: { nested: [1, 2] }, calls: [call('A'), call('B')] });

    const result = read(extra);

    expect(result.request).toEqual(extra);
  });

  it('takes a file of exactly the largest size', () => {
    const head =
      '{"uid":"U","date":"D","headcode":"H","calls":[{"location":"A"},{"location":"B"}],"x":"';
    const text = `${head}${'x'.repeat(MAX_TRAIN_FILE_CHARS - head.length - 2)}"}`;
    expect(text).toHaveLength(MAX_TRAIN_FILE_CHARS);

    expect(readTrainFile(text).ok).toBe(true);
  });

  it('refuses a file one character over the largest size as too big', () => {
    expect(readTrainFile(' '.repeat(MAX_TRAIN_FILE_CHARS + 1))).toEqual({
      ok: false,
      problem: 'tooBig',
    });
  });

  it('refuses text that is not JSON', () => {
    expect(readTrainFile('{"uid": ')).toEqual({ ok: false, problem: 'notJson' });
  });

  it('refuses an empty text as not JSON', () => {
    expect(readTrainFile('')).toEqual({ ok: false, problem: 'notJson' });
  });

  it.each([
    ['an array', [file()]],
    ['a number', 5],
    ['null', null],
    ['no uid', file({ uid: undefined })],
    ['no date', file({ date: undefined })],
    ['no headcode', file({ headcode: undefined })],
    ['a number for the uid', file({ uid: 12345 })],
    ['a number for the date', file({ date: 20300304 })],
    ['a number for the headcode', file({ headcode: 999 })],
    ['no calls', file({ calls: undefined })],
    ['calls that are not a list', file({ calls: { location: 'A' } })],
    ['one call', file({ calls: [call('A')] })],
    ['no call at all', file({ calls: [] })],
    ['a call with no location', file({ calls: [call('A'), { plannedPass: '22:00' }] })],
    ['a number for a location', file({ calls: [call('A'), { location: 7 }] })],
    ['a call that is null', file({ calls: [call('A'), null] })],
  ])('refuses %s as not a train file', (_, value) => {
    expect(read(value)).toEqual({ ok: false, problem: 'notATrainFile' });
  });

  it('answers without throwing for null, a number and an empty text', () => {
    expect(readTrainFile(null)).toEqual({ ok: false, problem: 'notATrainFile' });
    expect(readTrainFile(5)).toEqual({ ok: false, problem: 'notATrainFile' });
    expect(readTrainFile('')).toEqual({ ok: false, problem: 'notJson' });
  });
});
