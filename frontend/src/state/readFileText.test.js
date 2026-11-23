import { afterEach, describe, expect, it, vi } from 'vitest';
import { readFileText } from './readFileText.js';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('readFileText', () => {
  it('reads a file’s text', async () => {
    const file = new File(['one\ntwo'], 'a.jsonl');
    expect(await readFileText(file)).toBe('one\ntwo');
  });

  it('rejects with the reader’s error when the file cannot be read', async () => {
    const failure = new Error('gone');
    vi.stubGlobal(
      'FileReader',
      class {
        error = failure;
        readAsText() {
          this.onerror();
        }
      },
    );
    await expect(readFileText(new File([''], 'a.jsonl'))).rejects.toBe(failure);
  });

  it('rejects when what it is given is not a file', async () => {
    await expect(readFileText('not a file')).rejects.toThrow(/readAsText/);
  });
});
