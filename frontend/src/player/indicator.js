// The corner indicator (handover §4): `Auto · Original · Home`, `720p · Remote`, `480p · Remote · slow`.
import { AUTO, isSlowRung, rungName } from './ladder.js';
import { HOME_KBPS } from './throughput.js';

/**
 * `quality` is what the viewer chose, `rung` what is playing, `top` the session's highest rung, `kbps` the
 * median measured throughput (null before any measurement: then only the top rung counts as home).
 * Home is a median of at least 20 Mbps; slow is 480p or lower; amber when remote, slow or when Auto sits
 * below the top rung.
 */
export function indicatorModel({ quality, rung, top, kbps }) {
  const home = kbps === null ? rung === top : kbps >= HOME_KBPS;
  const slow = isSlowRung(rung);
  const parts = [];
  if (quality === AUTO) parts.push('Auto');
  parts.push(rungName(rung));
  parts.push(home ? 'Home' : 'Remote');
  if (slow) parts.push('slow');
  return {
    text: parts.join(' · '),
    amber: !home || slow || (quality === AUTO && rung !== top),
    slow,
    // Slow *and* a known speed below home: the slow-connection card needs a measurement, not a cautious start.
    measuredSlow: slow && kbps !== null && kbps < HOME_KBPS,
  };
}
