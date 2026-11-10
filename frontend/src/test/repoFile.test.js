import { describe, expect, it } from 'vitest';
import { readRepoJson } from './repoFile.js';

describe('readRepoJson', () => {
  // The path is from the repository root, not from frontend/ or the working directory: Stryker runs the
  // suite from a copy of frontend/, and the same call has to find the same file from there.
  it('reads a file by its path from the repository root', () => {
    expect(readRepoJson('frontend/package.json').name).toBe('cinefilm-frontend');
  });

  it('names the file when no folder above holds it', () => {
    expect(() => readRepoJson('no/such/table.json')).toThrow(
      /^no\/such\/table\.json was not found in any folder above /,
    );
  });
});
