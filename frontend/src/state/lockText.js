import { serverText } from '../api/client.js';

// The server's bare scan line ("A scan is running", no guidance): the screens have fuller words of their own for it.
const BARE_SCAN = /^a scan is running\.?$/i;

/** Whether a 409 names no holder beyond "a scan is running" (the bare line, or no text at all). */
export function isBareScanLock(failure) {
  const text = serverText(failure);
  return !text || BARE_SCAN.test(text);
}

/**
 * What a 409 from the library lock says. The server names what holds the lock; the bare scan line and no text at all
 * give the screen's own `fallback` (its scan wording, with its guidance); any other text is used as it is, ending in a
 * full stop. No JSX: shared by the Admin and Film detail mappers.
 */
export function lockText(failure, fallback) {
  if (isBareScanLock(failure)) return fallback;
  const text = serverText(failure);
  return /[.!?]$/.test(text) ? text : `${text}.`;
}
