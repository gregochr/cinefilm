import { describe, expect, it } from 'vitest';
import attention from './fixtures/admin-attention.json';
import forcedNeed from './fixtures/admin-forced-need.json';
import fullNeed from './fixtures/admin-full-need.json';

/**
 * The fixtures stand in for the API (`foundation-plan.md`, "Admin To do rules"): every row carries the precomputed
 * flags the server sends, and they are consistent with each other and with the counts. The rule itself lives on the
 * server; these only check that the fixture data does not contradict the contract.
 */
describe('the Admin To do fixtures', () => {
  describe('full-need rows', () => {
    const rows = fullNeed.films;

    it('every row has originalLanguage (a code or null) and both flags as booleans', () => {
      for (const row of rows) {
        expect(row).toHaveProperty('originalLanguage');
        expect(row.originalLanguage === null || typeof row.originalLanguage === 'string').toBe(
          true,
        );
        expect(typeof row.unresolved).toBe('boolean');
        expect(typeof row.beingChecked).toBe('boolean');
      }
      expect(rows.find((r) => r.filmId === 3).originalLanguage).toBe('de');
      expect(rows.find((r) => r.filmId === 5).originalLanguage).toBeNull();
    });

    it('a row is flagged only when it is NEEDED with no full subtitle, and then by exactly one flag', () => {
      for (const row of rows) {
        const inPlay = row.need === 'NEEDED' && row.fullSubtitle === null;
        expect(row.unresolved || row.beingChecked).toBe(inPlay);
        expect(row.unresolved && row.beingChecked).toBe(false);
      }
    });

    it('has a row in each state: unresolved, being checked and resolved', () => {
      expect(rows.filter((r) => r.unresolved).map((r) => r.title)).toEqual(['Rashomon']);
      expect(rows.filter((r) => r.beingChecked).map((r) => r.title)).toEqual(['Come and See']);
      expect(rows.filter((r) => r.need === 'NEEDED' && r.fullSubtitle !== null)).toHaveLength(1);
    });

    it('the counts are the number of rows in each state', () => {
      expect(fullNeed.counts).toEqual({
        needed: rows.filter((r) => r.need === 'NEEDED').length,
        unsure: rows.filter((r) => r.need === 'UNSURE').length,
        unknown: rows.filter((r) => r.need === 'UNKNOWN').length,
        notNeeded: rows.filter((r) => r.need === 'NOT_NEEDED').length,
        unresolved: rows.filter((r) => r.unresolved).length,
        beingChecked: rows.filter((r) => r.beingChecked).length,
        subtitled: rows.filter((r) => r.subtitled).length,
        // The listed not-needed film (Léon: English audio) plus the 530 English originals the list does not hold.
        inEnglish: 1 + 530,
      });
    });

    it('flags a row subtitled when it is needed and a full subtitle plays', () => {
      for (const row of rows) {
        expect(row.subtitled).toBe(row.need === 'NEEDED' && row.fullSubtitle !== null);
      }
    });

    it('has exactly the fields of the contract', () => {
      for (const row of rows) {
        expect(Object.keys(row).sort()).toEqual(
          [
            'beingChecked',
            'editionId',
            'editions',
            'filmId',
            'fullSubtitle',
            'need',
            'originalLanguage',
            'ownerChoice',
            'reason',
            'subtitled',
            'title',
            'unresolved',
            'year',
          ].sort(),
        );
      }
      expect(Object.keys(fullNeed.counts).sort()).toEqual(
        [
          'beingChecked',
          'inEnglish',
          'needed',
          'notNeeded',
          'subtitled',
          'unknown',
          'unresolved',
          'unsure',
        ].sort(),
      );
    });
  });

  describe('forced-need rows', () => {
    const rows = forcedNeed.films;

    it('every row has both flags as booleans', () => {
      for (const row of rows) {
        expect(typeof row.unresolved).toBe('boolean');
        expect(typeof row.beingChecked).toBe('boolean');
      }
    });

    it('a row is flagged exactly when it is today’s needed-missing row, by exactly one flag', () => {
      for (const row of rows) {
        const neededMissing = row.need === 'NEEDED' && !row.hasForcedSubtitle;
        expect(row.unresolved || row.beingChecked).toBe(neededMissing);
        expect(row.unresolved && row.beingChecked).toBe(false);
      }
    });

    it('a count and the time of its search are both present or both null, as the server sends them', () => {
      for (const row of rows) {
        expect(row.openSubtitlesForcedCount === null).toBe(row.openSubtitlesSearchedAt === null);
      }
    });

    it('UNSURE only means not searched yet, and an unsure row is not in play', () => {
      for (const row of rows.filter((r) => r.need === 'UNSURE')) {
        expect(row.openSubtitlesForcedCount).toBeNull();
        expect(row.unresolved || row.beingChecked).toBe(false);
      }
    });

    it('has a row in each state, and being checked means files are online', () => {
      expect(rows.filter((r) => r.unresolved).map((r) => r.title)).toEqual(['Taken']);
      expect(rows.filter((r) => r.beingChecked).map((r) => r.title)).toEqual(['Das Boot']);
      expect(rows.filter((r) => r.need === 'NEEDED' && r.hasForcedSubtitle)).toHaveLength(1);
      for (const row of rows.filter((r) => r.beingChecked)) {
        expect(row.openSubtitlesForcedCount).toBeGreaterThan(0);
      }
    });

    it('the counts are the number of rows in each state and the flags add up to needed-missing', () => {
      expect(forcedNeed.counts).toEqual({
        needed: rows.filter((r) => r.need === 'NEEDED').length,
        neededMissing: rows.filter((r) => r.need === 'NEEDED' && !r.hasForcedSubtitle).length,
        unsure: rows.filter((r) => r.need === 'UNSURE').length,
        unknown: rows.filter((r) => r.need === 'UNKNOWN').length,
        notNeeded: rows.filter((r) => r.need === 'NOT_NEEDED').length,
        unresolved: rows.filter((r) => r.unresolved).length,
        beingChecked: rows.filter((r) => r.beingChecked).length,
      });
      expect(forcedNeed.counts.unresolved + forcedNeed.counts.beingChecked).toBe(
        forcedNeed.counts.neededMissing,
      );
    });
  });

  describe('attention items', () => {
    const items = attention.items;

    it('every item has a boolean confident, true only for a needs-match item', () => {
      for (const item of items) {
        expect(typeof item.confident).toBe('boolean');
        if (item.kind !== 'needs-match') expect(item.confident).toBe(false);
      }
    });

    it('every candidate has an integer score', () => {
      const candidates = items.flatMap((i) => i.candidates);
      expect(candidates.length).toBeGreaterThan(0);
      for (const candidate of candidates) expect(Number.isInteger(candidate.score)).toBe(true);
    });

    it('has a confident needs-match item (a tagged refusal, one candidate at 100) and one that is not (62 against 58)', () => {
      const unmatched = items.filter((i) => i.kind === 'needs-match');
      const confident = unmatched.filter((i) => i.confident);
      const unsure = unmatched.filter((i) => !i.confident);
      expect(confident).toHaveLength(1);
      expect(confident[0].candidates.map((c) => c.score)).toEqual([100]);
      expect(unsure).toHaveLength(1);
      expect(unsure[0].candidates.map((c) => c.score)).toEqual([62, 58]);
    });
  });
});
