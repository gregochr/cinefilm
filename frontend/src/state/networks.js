// What this browser remembers about the networks it has been asked about: the answer to "Where are you watching?"
// for each network operator, in localStorage. Storage can be blocked or full, so every access is guarded and a
// failure just means nothing is remembered. No JSX, no component.

export const NETWORKS_KEY = 'cinefilm.networks';
export const MAX_NETWORKS = 50;

const MAX_OPERATOR_LENGTH = 100;
const ANSWERS = ['train', 'away'];

const validOperator = (value) =>
  typeof value === 'string' && value.length >= 1 && value.length <= MAX_OPERATOR_LENGTH;
const validEnvironment = (value) => ANSWERS.includes(value);

// The stored list, oldest answer first. Whatever cannot be read, or is not shaped as written, counts as absent;
// a repeated operator keeps its last answer, in the place of that last answer.
function read() {
  try {
    const parsed = JSON.parse(globalThis.localStorage.getItem(NETWORKS_KEY));
    // Not an array: an object or a number throws below, a string or null yields no entry; all count as absent.
    const byOperator = new Map();
    for (const entry of parsed) {
      if (!validOperator(entry?.operator) || !validEnvironment(entry.environment)) continue;
      byOperator.delete(entry.operator);
      byOperator.set(entry.operator, entry.environment);
    }
    return [...byOperator].map(([operator, environment]) => ({ operator, environment }));
  } catch {
    return [];
  }
}

function write(list) {
  try {
    globalThis.localStorage.setItem(NETWORKS_KEY, JSON.stringify(list));
    return true;
  } catch {
    return false;
  }
}

/** `[{ operator, environment }]`, the oldest answer first. */
export function rememberedNetworks() {
  return read();
}

/** The remembered answer for that operator ('train' or 'away'), or null. */
export function recallAnswer(operator) {
  return read().find((entry) => entry.operator === operator)?.environment ?? null;
}

/**
 * Remembers an answer as the newest, keeping the newest 50 networks. False, storing nothing, for an operator that
 * is not a string of 1 to 100 characters (an answer with no network to hang it on is not stored), an environment
 * that is not 'train' or 'away', or storage that refuses the write.
 */
export function rememberAnswer(operator, environment) {
  if (!validOperator(operator) || !validEnvironment(environment)) return false;
  const kept = read().filter((entry) => entry.operator !== operator);
  kept.push({ operator, environment });
  return write(kept.slice(-MAX_NETWORKS));
}

/** Forgets that operator and returns the networks left (unchanged when storage refuses the write). */
export function forgetNetwork(operator) {
  const list = read();
  const left = list.filter((entry) => entry.operator !== operator);
  return write(left) ? left : list;
}
