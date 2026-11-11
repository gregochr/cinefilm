// State of the header search combobox: the query, whether the dropdown is open, and which row is active. No JSX and
// no DOM: the transitions are a pure reducer (`comboboxReducer`) and a pure key reading (`interpretKey`), so they are
// tested without React. The component does the side effects (navigate, blur, scroll).
import { useCallback, useMemo, useReducer } from 'react';
import { search } from './search.js';

export const INITIAL_COMBOBOX = { query: '', open: false, active: 0 };

/** The dropdown shows from the first character after trimming; whitespace alone never opens it. */
export const hasQuery = (query) => query.trim().length > 0;

/**
 * Transitions:
 *  - `type`: a new query; opens when it has a character, and the first row becomes active;
 *  - `move`: the active row moves by `delta` through `rowCount` rows, clamped at both ends (no wrap);
 *  - `hover`: the mouse sets the active row;
 *  - `open`: focus returns with a query still in the box;
 *  - `close`: closes and keeps the query (outside click);
 *  - `clear`: empties and closes (Esc, or after opening a result).
 */
export function comboboxReducer(state, action) {
  switch (action.type) {
    case 'type':
      return { query: action.query, open: hasQuery(action.query), active: 0 };
    case 'move': {
      const last = Math.max(0, action.rowCount - 1);
      const active = Math.min(last, Math.max(0, state.active + action.delta));
      return active === state.active ? state : { ...state, active };
    }
    case 'hover':
      return action.index === state.active ? state : { ...state, active: action.index };
    case 'open':
      return state.open || !hasQuery(state.query) ? state : { ...state, open: true };
    case 'close':
      return state.open ? { ...state, open: false } : state;
    case 'clear':
      return INITIAL_COMBOBOX;
    default:
      return state;
  }
}

/**
 * What a key press means while the box has focus: `{ kind: 'move', delta }`, `{ kind: 'enter' }`, or
 * `{ kind: 'escape', blur }`. The first Escape clears and closes; once there is nothing to clear, the next blurs.
 * Anything else is `null`.
 */
export function interpretKey(key, state) {
  switch (key) {
    case 'ArrowDown':
      return state.open ? { kind: 'move', delta: 1 } : null;
    case 'ArrowUp':
      return state.open ? { kind: 'move', delta: -1 } : null;
    case 'Enter':
      return { kind: 'enter' };
    case 'Escape':
      return { kind: 'escape', blur: state.query === '' && !state.open };
    default:
      return null;
  }
}

/** Where a result opens. */
export function rowPath(groupKey, item) {
  if (groupKey === 'titles') return `/films/${item.id}`;
  if (groupKey === 'collections') return `/collections/${item.id}`;
  return `/people/${item.id}`;
}

/**
 * The option id: stable, distinct for a person who is in both Actors and Directors, and namespaced by `prefix` so the
 * header dropdown and the phone sheet never share an id when both lists are mounted.
 */
export const optionId = (groupKey, id, prefix = 'search-opt') => `${prefix}-${groupKey}-${id}`;

/** All rows of all groups in display order, each with its flat `index`, its option `id` and the `path` it opens. */
export function flattenRows(result, prefix) {
  const rows = [];
  for (const group of result.groups) {
    for (const item of group.items) {
      rows.push({
        index: rows.length,
        groupKey: group.key,
        id: optionId(group.key, item.id, prefix),
        path: rowPath(group.key, item),
        item,
      });
    }
  }
  return rows;
}

/** "Warrior" -> "W", "Tom Hanks" -> "TH", "Cher" -> "C": the first and last initial. */
export function initials(name) {
  const words = String(name ?? '')
    .split(/\s+/)
    .filter(Boolean);
  if (words.length === 0) return '';
  const first = Array.from(words[0])[0];
  const last = words.length > 1 ? Array.from(words[words.length - 1])[0] : '';
  return `${first}${last}`.toUpperCase();
}

/** "1 film", "3 films". */
export const filmCount = (n) => `${n} ${n === 1 ? 'film' : 'films'}`;

export const LOADING_TEXT = 'Loading…';
export const ERROR_TEXT = 'Search isn’t available right now. Enter searches the Library.';

/**
 * The text for the polite live region, one per state: Loading…, the error line, the number of matches, or the
 * no-match sentence. Empty when closed. The visible notes are not live regions themselves, so nothing is read twice.
 */
export function announcement({ open, status, result }) {
  if (!open) return '';
  if (status === 'loading') return LOADING_TEXT;
  if (status === 'error') return ERROR_TEXT;
  if (result.total === 0) return `No matches for “${result.query.trim()}”`;
  return result.total === 1 ? '1 result' : `${result.total} results`;
}

/**
 * The search box's state over the shared index: `{ state, result, rows, dispatch, setQuery }`. `idPrefix` namespaces the
 * option ids of this combobox (pass a `useId()`-based string).
 */
export function useSearchCombobox(index, idPrefix) {
  const [state, dispatch] = useReducer(comboboxReducer, INITIAL_COMBOBOX);
  // Runs on every keystroke, undebounced; memoised so a hover or an active-row change never searches again.
  const result = useMemo(() => search(index, state.query), [index, state.query]);
  const rows = useMemo(() => flattenRows(result, idPrefix), [result, idPrefix]);
  const setQuery = useCallback((query) => dispatch({ type: 'type', query }), []);
  return { state, result, rows, dispatch, setQuery };
}
