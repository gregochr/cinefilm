import { useEffect } from 'react';
import { useAdminContext } from '../../state/adminContext.js';

/**
 * After a Fix match or Edit editions saves, the row that opened it usually goes. When `signal` (the section's list)
 * next changes and focus has fallen to the page body, focus moves to the section's heading instead. Focus that is
 * somewhere real is left alone.
 */
export function useFallbackFocus(signal) {
  const { takeFocusFallback } = useAdminContext();
  useEffect(() => {
    if (!takeFocusFallback()) return;
    const active = document.activeElement;
    if (!active || active === document.body || !active.isConnected) {
      document.getElementById('admin-section-title')?.focus();
    }
  }, [signal, takeFocusFallback]);
}
