import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installStorage } from '../test/storage.js';
import {
  MAX_NETWORKS,
  NETWORKS_KEY,
  forgetNetwork,
  recallAnswer,
  rememberAnswer,
  rememberedNetworks,
} from './networks.js';

let storage;
beforeEach(() => {
  storage = installStorage();
});
afterEach(() => vi.unstubAllGlobals());

const stored = () => JSON.parse(storage.getItem(NETWORKS_KEY));
const seed = (value) =>
  storage.setItem(NETWORKS_KEY, typeof value === 'string' ? value : JSON.stringify(value));

// A storage that refuses: every read throws, or every write does.
function breakStorage({ read = false, write = false } = {}) {
  const broken = {
    getItem: read
      ? () => {
          throw new Error('blocked');
        }
      : storage.getItem,
    setItem: write
      ? () => {
          throw new Error('full');
        }
      : storage.setItem,
    removeItem: storage.removeItem,
  };
  vi.stubGlobal('localStorage', broken);
}

describe('remembering and recalling', () => {
  it('stores an answer under its operator and recalls it', () => {
    expect(rememberAnswer('AS5089 Virgin Media Limited', 'train')).toBe(true);
    expect(recallAnswer('AS5089 Virgin Media Limited')).toBe('train');
    expect(stored()).toEqual([{ operator: 'AS5089 Virgin Media Limited', environment: 'train' }]);
    expect(NETWORKS_KEY).toBe('cinefilm.networks');
  });

  it('remembers away as well', () => {
    rememberAnswer('AS1 A', 'away');
    expect(recallAnswer('AS1 A')).toBe('away');
  });

  it('recalls nothing for an operator it has not met', () => {
    rememberAnswer('AS1 A', 'train');
    expect(recallAnswer('AS2 B')).toBeNull();
  });

  it('lists the oldest answer first', () => {
    rememberAnswer('AS1 A', 'train');
    rememberAnswer('AS2 B', 'away');
    expect(rememberedNetworks()).toEqual([
      { operator: 'AS1 A', environment: 'train' },
      { operator: 'AS2 B', environment: 'away' },
    ]);
  });

  it('lists nothing when nothing is stored', () => {
    expect(rememberedNetworks()).toEqual([]);
  });

  it('moves a re-answered operator to the end and replaces its answer', () => {
    rememberAnswer('AS1 A', 'train');
    rememberAnswer('AS2 B', 'away');
    rememberAnswer('AS1 A', 'away');
    // The stored value itself holds each operator once.
    expect(stored()).toEqual([
      { operator: 'AS2 B', environment: 'away' },
      { operator: 'AS1 A', environment: 'away' },
    ]);
    expect(recallAnswer('AS1 A')).toBe('away');
  });

  it('keeps the newest 50 and drops the oldest when the 51st arrives', () => {
    expect(MAX_NETWORKS).toBe(50);
    for (let i = 1; i <= 50; i += 1) rememberAnswer(`AS${i} Net ${i}`, 'train');
    expect(rememberedNetworks()).toHaveLength(50);
    expect(recallAnswer('AS1 Net 1')).toBe('train');
    rememberAnswer('AS51 Net 51', 'away');
    const kept = rememberedNetworks();
    expect(kept).toHaveLength(50);
    expect(recallAnswer('AS1 Net 1')).toBeNull();
    expect(kept[0].operator).toBe('AS2 Net 2');
    expect(kept.at(-1)).toEqual({ operator: 'AS51 Net 51', environment: 'away' });
  });

  it('accepts an operator of 1 and of 100 characters', () => {
    expect(rememberAnswer('x', 'train')).toBe(true);
    expect(rememberAnswer('y'.repeat(100), 'away')).toBe(true);
    expect(recallAnswer('y'.repeat(100))).toBe('away');
  });
});

describe('what it refuses to store', () => {
  it('returns false and writes nothing for no operator', () => {
    const setItem = vi.spyOn(storage, 'setItem');
    expect(rememberAnswer(null, 'train')).toBe(false);
    expect(rememberAnswer(undefined, 'train')).toBe(false);
    expect(setItem).not.toHaveBeenCalled();
    expect(storage.getItem(NETWORKS_KEY)).toBeNull();
  });

  it('refuses an operator that is not a string of 1 to 100 characters', () => {
    expect(rememberAnswer('', 'train')).toBe(false);
    expect(rememberAnswer('z'.repeat(101), 'train')).toBe(false);
    expect(rememberAnswer(5, 'train')).toBe(false);
    expect(storage.getItem(NETWORKS_KEY)).toBeNull();
  });

  it('refuses an environment that is not train or away', () => {
    for (const environment of ['home', 'unknown', '', null, undefined, 'Train']) {
      expect(rememberAnswer('AS1 A', environment)).toBe(false);
    }
    expect(storage.getItem(NETWORKS_KEY)).toBeNull();
  });

  it('recalls nothing for no operator, even if something is stored', () => {
    rememberAnswer('AS1 A', 'train');
    expect(recallAnswer(null)).toBeNull();
    expect(recallAnswer(undefined)).toBeNull();
    expect(recallAnswer('')).toBeNull();
  });
});

describe('forgetting', () => {
  it('removes that operator and returns the networks left', () => {
    rememberAnswer('AS1 A', 'train');
    rememberAnswer('AS2 B', 'away');
    expect(forgetNetwork('AS1 A')).toEqual([{ operator: 'AS2 B', environment: 'away' }]);
    expect(stored()).toEqual([{ operator: 'AS2 B', environment: 'away' }]);
    expect(recallAnswer('AS1 A')).toBeNull();
  });

  it('leaves the list alone for an operator it does not hold', () => {
    rememberAnswer('AS1 A', 'train');
    expect(forgetNetwork('AS9 Z')).toEqual([{ operator: 'AS1 A', environment: 'train' }]);
  });

  it('returns an empty list when the last one goes', () => {
    rememberAnswer('AS1 A', 'train');
    expect(forgetNetwork('AS1 A')).toEqual([]);
    expect(stored()).toEqual([]);
  });

  it('returns the list unchanged, and does not throw, when the write is refused', () => {
    rememberAnswer('AS1 A', 'train');
    breakStorage({ write: true });
    expect(forgetNetwork('AS1 A')).toEqual([{ operator: 'AS1 A', environment: 'train' }]);
  });

  it('returns an empty list, and does not throw, when storage cannot be read', () => {
    breakStorage({ read: true });
    expect(forgetNetwork('AS1 A')).toEqual([]);
  });
});

describe('a stored value that is not what was written', () => {
  it.each([
    ['not JSON', 'not json {'],
    ['an object', '{"operator":"AS1 A","environment":"train"}'],
    ['a string', '"AS1 A"'],
    ['null', 'null'],
    ['a number', '7'],
  ])('counts as nothing when it is %s', (_name, raw) => {
    seed(raw);
    expect(rememberedNetworks()).toEqual([]);
    expect(recallAnswer('AS1 A')).toBeNull();
  });

  it('skips an entry that is not an object', () => {
    seed([null, 'AS1 A', 7, ['AS1 A', 'train'], { operator: 'AS2 B', environment: 'away' }]);
    expect(rememberedNetworks()).toEqual([{ operator: 'AS2 B', environment: 'away' }]);
  });

  it('skips an operator that is not a string of 1 to 100 characters', () => {
    seed([
      { operator: '', environment: 'train' },
      { operator: 5, environment: 'train' },
      { operator: null, environment: 'train' },
      { operator: 'q'.repeat(101), environment: 'train' },
      { environment: 'train' },
      { operator: 'AS1 A', environment: 'away' },
    ]);
    expect(rememberedNetworks()).toEqual([{ operator: 'AS1 A', environment: 'away' }]);
  });

  it('skips an environment that is not train or away', () => {
    seed([
      { operator: 'AS1 A', environment: 'home' },
      { operator: 'AS2 B', environment: 'Train' },
      { operator: 'AS3 C' },
      { operator: 'AS4 D', environment: 'train' },
    ]);
    expect(rememberedNetworks()).toEqual([{ operator: 'AS4 D', environment: 'train' }]);
  });

  it('keeps the last answer for a repeated operator, in its last place', () => {
    seed([
      { operator: 'AS1 A', environment: 'train' },
      { operator: 'AS2 B', environment: 'away' },
      { operator: 'AS1 A', environment: 'away' },
    ]);
    expect(rememberedNetworks()).toEqual([
      { operator: 'AS2 B', environment: 'away' },
      { operator: 'AS1 A', environment: 'away' },
    ]);
  });

  it('is repaired by the next answer: bad entries are not written back', () => {
    seed([
      { operator: 'AS1 A', environment: 'home' },
      { operator: 'AS2 B', environment: 'train' },
    ]);
    rememberAnswer('AS3 C', 'away');
    expect(stored()).toEqual([
      { operator: 'AS2 B', environment: 'train' },
      { operator: 'AS3 C', environment: 'away' },
    ]);
  });
});

describe('storage that throws', () => {
  it('reads as nothing and never throws', () => {
    breakStorage({ read: true });
    expect(rememberedNetworks()).toEqual([]);
    expect(recallAnswer('AS1 A')).toBeNull();
  });

  it('returns false from rememberAnswer when the write throws', () => {
    breakStorage({ write: true });
    expect(rememberAnswer('AS1 A', 'train')).toBe(false);
  });

  it('is nothing at all when there is no storage', () => {
    vi.stubGlobal('localStorage', undefined);
    expect(rememberedNetworks()).toEqual([]);
    expect(recallAnswer('AS1 A')).toBeNull();
    expect(rememberAnswer('AS1 A', 'train')).toBe(false);
    expect(forgetNetwork('AS1 A')).toEqual([]);
  });
});
