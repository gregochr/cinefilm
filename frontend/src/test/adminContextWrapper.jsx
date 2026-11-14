import { AdminContext } from '../state/adminContext.js';

/** A renderHook `wrapper` that provides the Admin context `value`. */
export function adminContextWrapper(value) {
  return function AdminContextWrapper({ children }) {
    return <AdminContext.Provider value={value}>{children}</AdminContext.Provider>;
  };
}
