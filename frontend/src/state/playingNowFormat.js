// The words and the match for the Playback section's "Playing now" card. Pure, no JSX: the Apple TV and native
// clients reuse it. The entries come from `GET /api/admin/summary`'s `playingNow`; the live HLS sessions from
// `GET /api/admin/transcoding`.
import { rungName } from '../player/ladder.js';
import { playingNow } from './adminCopy.js';
import { environmentLabel } from './healthFormat.js';
import { describeDevice } from './userAgent.js';

/**
 * The live HLS session an entry can be stopped through: the newest started session of the entry's edition, or null
 * (direct play has no session, so nothing to stop). A reload of the player can leave an older session of the same
 * edition behind; the newest is the one the viewer is on.
 */
export function liveSessionFor(editionId, sessions) {
  let newest = null;
  for (const session of sessions ?? []) {
    if (session.editionId !== editionId) continue;
    if (!newest || Date.parse(session.startedAt) > Date.parse(newest.startedAt)) newest = session;
  }
  return newest;
}

/**
 * One entry as the card draws it: the film, the device label, the detail line "{device} · {environment} · {quality}"
 * (the quality gains ", converting on the fly" while an HLS session exists; an unknown environment or quality is left
 * out), the Stop hint (07's line while the device is unknown), and the id of the session to stop (null: no Stop).
 */
export function playingNowRow(entry, sessions) {
  const session = liveSessionFor(entry.editionId, sessions);
  const device = describeDevice(entry.device, entry.touch);
  let quality = entry.quality ? rungName(entry.quality) : null;
  if (quality && session) quality = `${quality}, ${playingNow.converting}`;
  const environment = entry.environment ? environmentLabel(entry.environment) : null;
  return {
    playbackId: entry.playbackId,
    filmTitle: entry.filmTitle,
    device,
    detail: [device, environment, quality].filter(Boolean).join(' · '),
    hint: playingNow.stopHint(entry.device ? device : null),
    sessionId: session ? session.id : null,
  };
}
