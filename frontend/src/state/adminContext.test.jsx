import { renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AdminContext, useAdminContext } from './adminContext.js';

describe('useAdminContext', () => {
  it('returns what the layout provides', () => {
    const value = { settledAt: 'never|none', changed: 2 };
    const { result } = renderHook(() => useAdminContext(), {
      wrapper: ({ children }) => (
        <AdminContext.Provider value={value}>{children}</AdminContext.Provider>
      ),
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
