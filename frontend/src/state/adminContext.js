import { createContext, useContext } from 'react';

/** What every Admin section shares: the scan and Plex pollers, the reload stamps and the two dialogs' openers. */
export const AdminContext = createContext(null);

/** The shared Admin state; only valid under `AdminLayout`. */
export function useAdminContext() {
  const value = useContext(AdminContext);
  if (!value) throw new Error('useAdminContext must be used inside AdminLayout');
  return value;
}
