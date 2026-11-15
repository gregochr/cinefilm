// "12 min ago" for the Admin status strip. Pure: the caller passes `now`, so the boundaries are testable.

const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

/**
 * How long before `now` (epoch ms) an ISO instant was: "just now" under a minute (a time in the future, from clock
 * skew, too), "{n} min ago" under an hour, "{n} hour(s) ago" under a day, then "{n} day(s) ago". Whole units,
 * rounded down.
 */
export function timeAgo(iso, now) {
  const elapsed = now - new Date(iso).getTime();
  if (elapsed < MINUTE_MS) return 'just now';
  if (elapsed < HOUR_MS) return `${Math.floor(elapsed / MINUTE_MS)} min ago`;
  if (elapsed < DAY_MS) {
    const hours = Math.floor(elapsed / HOUR_MS);
    return `${hours} ${hours === 1 ? 'hour' : 'hours'} ago`;
  }
  const days = Math.floor(elapsed / DAY_MS);
  return `${days} ${days === 1 ? 'day' : 'days'} ago`;
}
