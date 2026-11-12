// Where the film is being watched: the rules that turn a measured latency and a remembered answer into an
// environment, and the words of the question. Pure; no DOM, no storage.

/** A round trip under this is taken to be home. A guess: every viewing records its latency, so it can move. */
export const HOME_LATENCY_MS = 20;
export const QUESTION = 'Where are you watching?';

const RELAYS = ['akamai', 'cloudflare', 'fastly'];

/** Whether a measured latency is one that calls for a lookup: a number, and not under the home line. */
export function needsLookup(latencyMs) {
  return typeof latencyMs === 'number' && latencyMs >= HOME_LATENCY_MS;
}

/**
 * `{ environment, ask }`. No latency: unknown. Under 20 ms: home. Otherwise the remembered `answer` ('train' or
 * 'away') decides; with none the environment is unknown and the question is due.
 */
export function classify({ latencyMs, answer } = {}) {
  if (typeof latencyMs !== 'number' || Number.isNaN(latencyMs)) {
    return { environment: 'unknown', ask: false };
  }
  if (latencyMs < HOME_LATENCY_MS) return { environment: 'home', ask: false };
  if (answer === 'train' || answer === 'away') return { environment: answer, ask: false };
  return { environment: 'unknown', ask: true };
}

/**
 * True when the operator's name contains Akamai, Cloudflare or Fastly. A relay (iCloud Private Relay, Safari
 * hiding the address) goes out through one of them wherever the viewer is, so its name says nothing about the
 * network.
 */
export function isRelay(operator) {
  if (typeof operator !== 'string') return false;
  const name = operator.toLowerCase();
  return RELAYS.some((relay) => name.includes(relay));
}

/** The card's paragraph, for an operator's display name or null. */
export function questionText(name) {
  if (name) {
    return `CineFilm hasn’t seen this network before: ${name}. It will remember your answer on this device.`;
  }
  return 'CineFilm can’t tell which network this is, so it will ask again next time.';
}
