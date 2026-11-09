import { vi } from 'vitest';

/** An in-memory `localStorage` for tests (Node's own global one is not usable under jsdom). */
export function installStorage(initial = {}) {
  const data = new Map(Object.entries(initial));
  const storage = {
    getItem: (key) => (data.has(key) ? data.get(key) : null),
    setItem: (key, value) => data.set(key, String(value)),
    removeItem: (key) => data.delete(key),
    clear: () => data.clear(),
  };
  vi.stubGlobal('localStorage', storage);
  Object.defineProperty(window, 'localStorage', { value: storage, configurable: true });
  return storage;
}
