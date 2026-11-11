import { describe, expect, it } from 'vitest';
import {
  INITIAL_COMBOBOX,
  announcement,
  comboboxReducer,
  filmCount,
  flattenRows,
  hasQuery,
  initials,
  interpretKey,
  optionId,
  rowPath,
} from './useSearchCombobox.js';

const state = (over) => ({ ...INITIAL_COMBOBOX, ...over });

describe('comboboxReducer', () => {
  it('opens on the first character and makes the first row active', () => {
    const next = comboboxReducer(state({ active: 4 }), { type: 'type', query: 'w' });
    expect(next).toEqual({ query: 'w', open: true, active: 0 });
  });

  it('stays closed for whitespace and for an empty query', () => {
    expect(comboboxReducer(INITIAL_COMBOBOX, { type: 'type', query: '   ' }).open).toBe(false);
    expect(comboboxReducer(state({ open: true }), { type: 'type', query: '' }).open).toBe(false);
  });

  it('moves the active row and clamps at both ends, without wrapping', () => {
    const at = (active, delta) =>
      comboboxReducer(state({ open: true, active }), { type: 'move', delta, rowCount: 3 }).active;
    expect(at(0, 1)).toBe(1);
    expect(at(1, 1)).toBe(2);
    expect(at(2, 1)).toBe(2);
    expect(at(0, -1)).toBe(0);
    expect(at(2, -1)).toBe(1);
  });

  it('keeps the same state object when a move changes nothing', () => {
    const s = state({ open: true, active: 0 });
    expect(comboboxReducer(s, { type: 'move', delta: -1, rowCount: 3 })).toBe(s);
    expect(comboboxReducer(s, { type: 'move', delta: 1, rowCount: 0 })).toBe(s);
  });

  it('hover sets the active row', () => {
    const s = state({ open: true, active: 0 });
    expect(comboboxReducer(s, { type: 'hover', index: 3 }).active).toBe(3);
    expect(comboboxReducer({ ...s, active: 3 }, { type: 'hover', index: 3 }).active).toBe(3);
    const same = state({ open: true, active: 2 });
    expect(comboboxReducer(same, { type: 'hover', index: 2 })).toBe(same);
  });

  it('open only reopens when there is a query', () => {
    expect(comboboxReducer(state({ query: 'wa' }), { type: 'open' }).open).toBe(true);
    expect(comboboxReducer(INITIAL_COMBOBOX, { type: 'open' })).toBe(INITIAL_COMBOBOX);
    const open = state({ query: 'wa', open: true });
    expect(comboboxReducer(open, { type: 'open' })).toBe(open);
  });

  it('close keeps the query; clear empties it', () => {
    const open = state({ query: 'wa', open: true, active: 2 });
    expect(comboboxReducer(open, { type: 'close' })).toEqual({
      query: 'wa',
      open: false,
      active: 2,
    });
    const closed = state({ query: 'wa' });
    expect(comboboxReducer(closed, { type: 'close' })).toBe(closed);
    expect(comboboxReducer(open, { type: 'clear' })).toEqual(INITIAL_COMBOBOX);
  });

  it('ignores an unknown action', () => {
    expect(comboboxReducer(INITIAL_COMBOBOX, { type: 'nope' })).toBe(INITIAL_COMBOBOX);
  });
});

describe('interpretKey', () => {
  const open = state({ query: 'wa', open: true });

  it('arrows move only while the dropdown is open', () => {
    expect(interpretKey('ArrowDown', open)).toEqual({ kind: 'move', delta: 1 });
    expect(interpretKey('ArrowUp', open)).toEqual({ kind: 'move', delta: -1 });
    expect(interpretKey('ArrowDown', INITIAL_COMBOBOX)).toBeNull();
    expect(interpretKey('ArrowUp', INITIAL_COMBOBOX)).toBeNull();
  });

  it('Enter is always Enter', () => {
    expect(interpretKey('Enter', open)).toEqual({ kind: 'enter' });
    expect(interpretKey('Enter', INITIAL_COMBOBOX)).toEqual({ kind: 'enter' });
  });

  it('the first Escape clears; the next, with nothing left, blurs', () => {
    expect(interpretKey('Escape', open)).toEqual({ kind: 'escape', blur: false });
    expect(interpretKey('Escape', state({ query: 'wa' }))).toEqual({ kind: 'escape', blur: false });
    expect(interpretKey('Escape', INITIAL_COMBOBOX)).toEqual({ kind: 'escape', blur: true });
  });

  it('other keys mean nothing', () => {
    expect(interpretKey('a', open)).toBeNull();
  });
});

describe('rows', () => {
  const result = {
    groups: [
      { key: 'titles', items: [{ id: 1 }, { id: 2 }] },
      { key: 'collections', items: [{ id: 3 }] },
      { key: 'actors', items: [{ id: 9 }] },
      { key: 'directors', items: [{ id: 9 }] },
    ],
  };

  it('flattens the groups in order with distinct ids and the path each opens', () => {
    const rows = flattenRows(result);
    expect(rows.map((r) => [r.index, r.id, r.path])).toEqual([
      [0, 'search-opt-titles-1', '/films/1'],
      [1, 'search-opt-titles-2', '/films/2'],
      [2, 'search-opt-collections-3', '/collections/3'],
      [3, 'search-opt-actors-9', '/people/9'],
      [4, 'search-opt-directors-9', '/people/9'],
    ]);
    expect(rows[0].item).toBe(result.groups[0].items[0]);
  });

  it('names paths and option ids', () => {
    expect(rowPath('titles', { id: 5 })).toBe('/films/5');
    expect(rowPath('collections', { id: 5 })).toBe('/collections/5');
    expect(rowPath('directors', { id: 5 })).toBe('/people/5');
    expect(optionId('actors', 7)).toBe('search-opt-actors-7');
    expect(optionId('actors', 7, 'x-opt')).toBe('x-opt-actors-7');
  });
});

describe('text helpers', () => {
  it('initials are the first and last initial', () => {
    expect(initials('Tom Hanks')).toBe('TH');
    expect(initials('Daniel Day Lewis')).toBe('DL');
    expect(initials('cher')).toBe('C');
    expect(initials('  ')).toBe('');
    expect(initials(undefined)).toBe('');
    expect(initials('Émile Zola')).toBe('ÉZ');
  });

  it('counts films', () => {
    expect(filmCount(1)).toBe('1 film');
    expect(filmCount(0)).toBe('0 films');
    expect(filmCount(3)).toBe('3 films');
  });

  it('hasQuery trims', () => {
    expect(hasQuery(' a ')).toBe(true);
    expect(hasQuery('  ')).toBe(false);
  });

  it('announces counts and the no-match text, for loading and error, and nothing while closed', () => {
    const ready = (total, query = 'wa') => ({
      open: true,
      status: 'ready',
      result: { total, query },
    });
    expect(announcement(ready(12))).toBe('12 results');
    expect(announcement(ready(1))).toBe('1 result');
    expect(announcement(ready(0, ' zzz '))).toBe('No matches for “zzz”');
    expect(announcement({ ...ready(3), open: false })).toBe('');
    expect(announcement({ ...ready(3), status: 'loading' })).toBe('Loading…');
    expect(announcement({ ...ready(3), status: 'error' })).toBe(
      'Search isn’t available right now. Enter searches the Library.',
    );
    expect(announcement({ open: false, status: 'loading', result: { total: 0, query: '' } })).toBe(
      '',
    );
  });
});
