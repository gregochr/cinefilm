import { describe, expect, it } from 'vitest';
import { readRepoJson } from '../test/repoFile.js';
import { MESSAGES, validateArrangement } from './editionRules.js';

// The one table of cases both sides read: backend EditionRulesTest runs the same file against EditionRules.java.
const table = readRepoJson('backend/src/test/resources/shared/edition-validation-cases.json');

describe('validateArrangement', () => {
  it.each(table.cases.map((c) => [c.name, c]))('%s', (_name, c) => {
    expect(validateArrangement(c.request, c.fileIds)).toEqual(c.errors);
  });

  it('the table uses every message, so the wording cannot change unnoticed on either side', () => {
    const used = new Set(table.cases.flatMap((c) => c.errors.map((e) => e.message)));
    Object.values(MESSAGES).forEach((message) => expect(used).toContain(message));
    expect([...used].some((m) => m.startsWith('Another edition is already called '))).toBe(true);
    expect([...used].some((m) => m.startsWith('Two files are both part '))).toBe(true);
    expect([...used].some((m) => m.endsWith(' is missing.'))).toBe(true);
    expect([...used].some((m) => m.endsWith(' are missing.'))).toBe(true);
  });

  it('treats missing lists as empty', () => {
    expect(validateArrangement({}, [1])).toEqual([
      { field: 'files', message: MESSAGES.unplaced },
      { field: 'editions', message: MESSAGES.noEditions },
    ]);
    expect(
      validateArrangement({ editions: [{ id: 5, name: 'Original', default: true }] }, []),
    ).toEqual([{ field: 'editions[0].files', message: MESSAGES.emptyEdition }]);
  });
});
