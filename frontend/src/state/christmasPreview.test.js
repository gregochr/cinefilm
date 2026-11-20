import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installStorage } from '../test/storage.js';
import { PREVIEW_OPEN_KEY, readPreviewOpen, writePreviewOpen } from './christmasPreview.js';

let storage;
beforeEach(() => {
  storage = installStorage();
});
afterEach(() => vi.unstubAllGlobals());

const refusing = (which) =>
  vi.stubGlobal('localStorage', {
    getItem: () => {
      if (which === 'read') throw new Error('blocked');
      return null;
    },
    setItem: () => {
      throw new Error('full');
    },
  });

describe('the remembered preview state', () => {
  it('is open until the owner hides it', () => {
    expect(readPreviewOpen()).toBe(true);
  });

  it('remembers closed and open', () => {
    writePreviewOpen(false);
    expect(storage.getItem(PREVIEW_OPEN_KEY)).toBe('false');
    expect(readPreviewOpen()).toBe(false);
    writePreviewOpen(true);
    expect(readPreviewOpen()).toBe(true);
  });

  it('reads as open when storage refuses to be read', () => {
    refusing('read');
    expect(readPreviewOpen()).toBe(true);
  });

  it('does not throw when storage refuses a write', () => {
    refusing('write');
    expect(() => writePreviewOpen(false)).not.toThrow();
  });
});
