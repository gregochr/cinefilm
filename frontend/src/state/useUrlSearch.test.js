import { act, renderHook } from '@testing-library/react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { routerWrapper } from '../test/routerWrapper.jsx';
import { SEARCH_DEBOUNCE_MS, useUrlSearch } from './useUrlSearch.js';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

function setup(path = '/library') {
  return renderHook(
    () => {
      const [params, setParams] = useSearchParams();
      const navigate = useNavigate();
      return { ...useUrlSearch(params, setParams), params, navigate };
    },
    { wrapper: routerWrapper(path) },
  );
}

const settle = () => act(() => vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS + 10));

describe('useUrlSearch', () => {
  it('starts from the URL, and typing debounces into q', () => {
    const { result } = setup('/library?q=heat');
    expect(result.current.text).toBe('heat');
    act(() => result.current.setText('  war '));
    expect(result.current.params.get('q')).toBe('heat');
    settle();
    expect(result.current.params.get('q')).toBe('war');
    expect(result.current.q).toBe('war');
    expect(result.current.text).toBe('  war ');
  });

  it('typing keeps going while its own write lands', () => {
    const { result } = setup();
    act(() => result.current.setText('wa'));
    settle();
    act(() => result.current.setText('war'));
    expect(result.current.text).toBe('war');
    settle();
    expect(result.current.params.get('q')).toBe('war');
  });

  it('clearing the text removes q', () => {
    const { result } = setup('/library?q=heat');
    act(() => result.current.setText(''));
    settle();
    expect(result.current.params.has('q')).toBe(false);
  });

  it('adopts a q changed by navigation and does not write the old one back', () => {
    const { result } = setup('/library?q=heat');
    act(() => result.current.navigate('/library?q=wa', { replace: true }));
    expect(result.current.text).toBe('wa');
    settle();
    expect(result.current.params.get('q')).toBe('wa');
    expect(result.current.text).toBe('wa');
  });

  it('adopts a q that was added when there was none, and one that was removed', () => {
    const { result } = setup('/library');
    act(() => result.current.navigate('/library?q=wa'));
    expect(result.current.text).toBe('wa');
    act(() => result.current.navigate('/library'));
    expect(result.current.text).toBe('');
    settle();
    expect(result.current.params.has('q')).toBe(false);
  });
});
