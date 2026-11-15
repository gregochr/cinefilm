import { describe, expect, it } from 'vitest';
import attention from './fixtures/admin-attention.json';
import attentionMany from './fixtures/admin-attention-many.json';
import forcedNeed from './fixtures/admin-forced-need.json';
import forcedNeedMany from './fixtures/admin-forced-need-many.json';
import fullNeed from './fixtures/admin-full-need.json';
import fullNeedMany from './fixtures/admin-full-need-many.json';
import { buildTodoGroups } from '../state/adminTodo.js';

const keys = (value) => Object.keys(value).sort();

/**
 * The "many" fixtures hold more than five rows in every To do group. They must be shaped exactly like the
 * responses the base fixtures stand in for, so a screen test on them proves the same contract.
 */
describe('the many-rows To do fixtures', () => {
  it('shape every attention item like the attention fixture', () => {
    for (const row of attentionMany.items) {
      expect(keys(row)).toEqual(keys(attention.items[0]));
      for (const candidate of row.candidates) {
        expect(keys(candidate)).toEqual(keys(attention.items[0].candidates[0]));
      }
    }
  });

  it('shape every need row and the counts like the need fixtures', () => {
    for (const row of fullNeedMany.films) expect(keys(row)).toEqual(keys(fullNeed.films[0]));
    for (const row of forcedNeedMany.films) expect(keys(row)).toEqual(keys(forcedNeed.films[0]));
    expect(keys(fullNeedMany.counts)).toEqual(keys(fullNeed.counts));
    expect(keys(forcedNeedMany.counts)).toEqual(keys(forcedNeed.counts));
  });

  it('put more than five rows in each group, with a confident and a non-confident match and an excluded kind', () => {
    const groups = buildTodoGroups({
      attention: attentionMany.items,
      full: fullNeedMany.films,
      forced: forcedNeedMany.films,
    });

    expect(groups.map((g) => [g.key, g.total])).toEqual([
      ['pickFilm', 7],
      ['severalFiles', 6],
      ['fullMissing', 7],
      ['forcedQuestion', 7],
    ]);
    const matches = attentionMany.items.filter((i) => i.kind === 'needs-match');
    expect(matches.some((i) => i.confident)).toBe(true);
    expect(matches.some((i) => !i.confident)).toBe(true);
    expect(attentionMany.items.some((i) => i.kind === 'protected')).toBe(true);
  });

  it('keep the counts the server would send for their own rows', () => {
    expect(fullNeedMany.counts.unresolved).toBe(
      fullNeedMany.films.filter((r) => r.unresolved).length,
    );
    expect(forcedNeedMany.counts.unresolved).toBe(
      forcedNeedMany.films.filter((r) => r.unresolved).length,
    );
  });
});
