import { describe, expect, it } from 'vitest';
import {
  DEFAULT_FULL_NEED_FILTER,
  FULL_NEED_FILTERS,
  audioNote,
  filterFullNeed,
  fullNeedCounts,
  fullNeedReason,
  fullNeedWords,
} from './fullNeed.js';

const row = (need, over = {}) => ({ filmId: 1, need, reason: 'OWNER', editions: [], ...over });
const ROWS = [row('NEEDED'), row('UNSURE'), row('UNKNOWN'), row('NOT_NEEDED'), row('NOT_NEEDED')];

describe('filters', () => {
  it('are Needed, Unsure and Not needed, starting on Needed', () => {
    expect(FULL_NEED_FILTERS.map((f) => f.label)).toEqual(['Needed', 'Unsure', 'Not needed']);
    expect(DEFAULT_FULL_NEED_FILTER).toBe('needed');
  });

  it('list the rows of each need, an unknown film waiting with the unsure ones', () => {
    expect(filterFullNeed(ROWS, 'needed').map((r) => r.need)).toEqual(['NEEDED']);
    expect(filterFullNeed(ROWS, 'unsure').map((r) => r.need)).toEqual(['UNSURE', 'UNKNOWN']);
    expect(filterFullNeed(ROWS, 'not-needed')).toHaveLength(2);
    expect(filterFullNeed(ROWS, 'nonsense')).toEqual([]);
  });

  it('count each chip from the rows', () => {
    expect(fullNeedCounts(ROWS)).toEqual({ needed: 1, unsure: 2, 'not-needed': 2 });
  });
});

describe('wording', () => {
  it('words each need', () => {
    expect(fullNeedWords('NEEDED')).toBe('Needed');
    expect(fullNeedWords('NOT_NEEDED')).toBe('Not needed');
    expect(fullNeedWords('UNSURE')).toBe('Unsure');
    expect(fullNeedWords('UNKNOWN')).toBe('Unsure');
    expect(fullNeedWords('SOMETHING_NEW')).toBe('Unsure');
  });

  it('words each reason in plain language', () => {
    const words = (reason) => fullNeedReason({ reason });
    expect(words('FOREIGN_AUDIO')).toBe('Audio is in another language');
    expect(words('ENGLISH_AUDIO')).toBe('Audio is English');
    expect(words('UNLABELLED_AUDIO')).toBe('Audio language not labelled');
    expect(words('ENGLISH_FILM')).toBe('English-language film');
    expect(words('OWNER')).toBe('Set by you');
    expect(words('NO_LANGUAGES')).toBe('Waiting for TMDB');
    expect(words('SOMETHING_NEW')).toBe('');
  });

  it('names the audio of one edition, or of each of several', () => {
    expect(audioNote(row('NEEDED', { editions: [{ name: 'Original', audio: 'French' }] }))).toBe(
      'Audio: French',
    );
    expect(
      audioNote(
        row('UNSURE', {
          editions: [
            { name: 'Theatrical Cut', audio: null },
            { name: 'Director’s Cut', audio: ' English ' },
          ],
        }),
      ),
    ).toBe('Audio: Theatrical Cut: not labelled; Director’s Cut: English');
  });

  it('says nothing about the audio of a film with no present edition', () => {
    expect(audioNote(row('NEEDED'))).toBe('');
    expect(audioNote({ need: 'NEEDED' })).toBe('');
  });

  it('calls a blank label not labelled', () => {
    expect(audioNote(row('UNSURE', { editions: [{ name: 'Original', audio: '  ' }] }))).toBe(
      'Audio: not labelled',
    );
  });
});
