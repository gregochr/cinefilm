import { serverText } from '../api/client.js';

// The server's bare scan line ("A scan is running", no guidance): the screens have fuller words of their own for it.
const BARE_SCAN = /^a scan is running\.?$/i;

/**
 * What a 409 from the library lock says. The server names what holds the lock; the bare scan line and no text at all
 * give the screen's own `fallback` (its scan wording, with its guidance); any other text is used as it is, ending in a
 * full stop. No JSX: shared by the Admin and Film detail mappers.
 */
export function lockText(failure, fallback) {
  const text = serverText(failure);
  if (!text || BARE_SCAN.test(text)) return fallback;
  return /[.!?]$/.test(text) ? text : `${text}.`;
}
