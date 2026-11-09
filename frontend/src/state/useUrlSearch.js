import { useEffect, useRef, useState } from 'react';
import { useDebouncedValue } from './useDebouncedValue.js';

export const SEARCH_DEBOUNCE_MS = 250;

/**
 * The Library search box: `text` is what is typed, `q` is the trimmed search kept in the URL's `q`
 * parameter once typing pauses. `params` and `setParams` are from `useSearchParams`.
 */
export function useUrlSearch(params, setParams) {
  const q = params.get('q') || '';
  const [text, setText] = useState(q);
  const debounced = useDebouncedValue(text, SEARCH_DEBOUNCE_MS);
  const paramsRef = useRef(params);
  useEffect(() => {
    paramsRef.current = params;
  });
  useEffect(() => {
    const current = paramsRef.current;
    if (debounced === (current.get('q') || '')) return;
    const next = new URLSearchParams(current);
    if (debounced.trim()) next.set('q', debounced.trim());
    else next.delete('q');
    setParams(next, { replace: true });
  }, [debounced, setParams]);
  return { q, text, setText };
}
