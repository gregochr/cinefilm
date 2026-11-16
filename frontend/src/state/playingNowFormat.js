// The words and the match for the Playback section's "Now playing" card. Pure, no JSX: the Apple TV and native
// clients reuse it. The entries come from `GET /api/admin/summary`'s `playingNow`; the live HLS sessions from
// `GET /api/admin/transcoding`.
import { rungName } from '../player/ladder.js';
import { playingNow } from './adminCopy.js';
import { environmentLabel } from './healthFormat.js';
import { describeDevice } from './userAgent.js';

/**
 * The live HLS session an entry can be stopped through, or null (direct play has no session, so nothing to stop).
 * A session names the viewing it serves (`playbackId`): the entry's own session is the one with its `playbackId`, so
 * Stop on one row never ends another device's playback of the same edition. Only a session that names no viewing (an
 * older page) is matched by the entry's edition. The server keeps at most one session per edition
 * (`SessionManager.create` replaces any live one), so taking the newest started is only a defence.
 */
export function liveSessionFor(editionId, sessions, playbackId = null) {
  const same = (sessions ?? []).filter((session) => session.editionId === editionId);
  const named = playbackId ? same.filter((session) => session.playbackId === playbackId) : [];
  const candidates = named.length > 0 ? named : same.filter((session) => !session.playbackId);
  let newest = null;
  for (const session of candidates) {
    if (!newest || Date.parse(session.startedAt) > Date.parse(newest.startedAt)) newest = session;
  }
  return newest;
}

/**
 * One entry as the card draws it: the film, the device label, the detail line "{device} · {environment} · {quality}"
 * (the quality gains ", converting on the fly" while an HLS session exists; an unknown environment or quality is left
 * out), the Stop hint, and the id of the session to stop (null: no Stop).
 */
export function playingNowRow(entry, sessions) {
  const session = liveSessionFor(entry.editionId, sessions, entry.playbackId);
  const device = describeDevice(entry.device, entry.touch);
  let quality = entry.quality ? rungName(entry.quality) : null;
  if (quality && session) quality = `${quality}, ${playingNow.converting}`;
  const environment = entry.environment ? environmentLabel(entry.environment) : null;
  return {
    playbackId: entry.playbackId,
    filmTitle: entry.filmTitle,
    device,
    detail: [device, environment, quality].filter(Boolean).join(' · '),
    hint: playingNow.stopHint(device),
    sessionId: session ? session.id : null,
  };
}
