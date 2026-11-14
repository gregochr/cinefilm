import { renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { adminContextWrapper } from '../test/adminContextWrapper.jsx';
import { useAdminContext } from './adminContext.js';

describe('useAdminContext', () => {
  it('returns what the layout provides', () => {
    const value = { settledAt: 'never|none', changed: 2 };
    const { result } = renderHook(() => useAdminContext(), {
      wrapper: adminContextWrapper(value),
    });
    expect(result.current).toBe(value);
  });

  it('refuses to run outside the Admin layout', () => {
    const quiet = vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(() => renderHook(() => useAdminContext())).toThrow(
      'useAdminContext must be used inside AdminLayout',
    );
    quiet.mockRestore();
  });
});
