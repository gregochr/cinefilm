import { describe, expect, it } from 'vitest';
import {
  HOME_LATENCY_MS,
  QUESTION,
  classify,
  isRelay,
  needsLookup,
  questionText,
} from './environment.js';

describe('classify', () => {
  it('calls 19 ms home, with no question', () => {
    expect(classify({ latencyMs: 19, answer: null })).toEqual({ environment: 'home', ask: false });
  });

  it('does not call 20 ms or 21 ms home', () => {
    expect(HOME_LATENCY_MS).toBe(20);
    expect(classify({ latencyMs: 20, answer: null })).toEqual({
      environment: 'unknown',
      ask: true,
    });
    expect(classify({ latencyMs: 21, answer: null })).toEqual({
      environment: 'unknown',
      ask: true,
    });
  });

  it('calls 0 ms home', () => {
    expect(classify({ latencyMs: 0, answer: null })).toEqual({ environment: 'home', ask: false });
  });

  it('home wins over a remembered answer', () => {
    expect(classify({ latencyMs: 3, answer: 'train' })).toEqual({
      environment: 'home',
      ask: false,
    });
  });

  it('takes a remembered train answer, with no question', () => {
    expect(classify({ latencyMs: 48, answer: 'train' })).toEqual({
      environment: 'train',
      ask: false,
    });
  });

  it('takes a remembered away answer, with no question', () => {
    expect(classify({ latencyMs: 48, answer: 'away' })).toEqual({
      environment: 'away',
      ask: false,
    });
  });

  it('asks when the answer is neither train nor away', () => {
    for (const answer of [null, undefined, 'home', 'unknown', '', 42]) {
      expect(classify({ latencyMs: 48, answer })).toEqual({ environment: 'unknown', ask: true });
    }
  });

  it('is unknown, with no question, when there is no figure', () => {
    for (const latencyMs of [null, undefined, NaN, '48']) {
      expect(classify({ latencyMs, answer: 'train' })).toEqual({
        environment: 'unknown',
        ask: false,
      });
    }
    expect(classify()).toEqual({ environment: 'unknown', ask: false });
  });
});

describe('needsLookup', () => {
  it('is false under 20 ms and true from 20 ms', () => {
    expect(needsLookup(19)).toBe(false);
    expect(needsLookup(20)).toBe(true);
    expect(needsLookup(21)).toBe(true);
  });

  it('is false for no figure', () => {
    expect(needsLookup(null)).toBe(false);
    expect(needsLookup(undefined)).toBe(false);
    expect(needsLookup(NaN)).toBe(false);
    expect(needsLookup('48')).toBe(false);
  });
});

describe('isRelay', () => {
  it.each([
    ['AS20940 Akamai International B.V.'],
    ['AS13335 CLOUDFLARE, INC.'],
    ['AS54113 Fastly, Inc.'],
    ['AS1 My Cloudflare Relay Ltd'],
    ['akamai'],
    ['FASTLY'],
  ])('is true for %s', (operator) => {
    expect(isRelay(operator)).toBe(true);
  });

  it('is false for an ordinary operator', () => {
    expect(isRelay('AS5089 Virgin Media Limited')).toBe(false);
    expect(isRelay('AS64500 Example Rail')).toBe(false);
  });

  it('is false when there is no name', () => {
    expect(isRelay(null)).toBe(false);
    expect(isRelay(undefined)).toBe(false);
    expect(isRelay(13335)).toBe(false);
    expect(isRelay('')).toBe(false);
  });
});

describe('questionText', () => {
  it('names the network when there is one', () => {
    expect(questionText('Virgin Media Limited')).toBe(
      'CineFilm hasn’t seen this network before: Virgin Media Limited. It will remember your answer on this device.',
    );
  });

  it('says it cannot tell when there is none', () => {
    expect(questionText(null)).toBe(
      'CineFilm can’t tell which network this is, so it will ask again next time.',
    );
  });

  it('asks the question the handover words', () => {
    expect(QUESTION).toBe('Are you on a train?');
  });
});
