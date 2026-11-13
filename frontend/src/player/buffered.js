// How much film is banked ahead of the playhead. hls.js plays across a small hole between buffered ranges,
// so a hole of up to HOLE_S counts as continuous; the native engine's stuck check keeps its own, stricter rule.

export const HOLE_S = 0.5; // the largest gap between two buffered ranges that still counts as continuous

/** Seconds buffered ahead of `video`'s playhead, bridging holes of up to HOLE_S; 0 when no range holds it. */
export function bankedAhead(video) {
  const { buffered, currentTime } = video;
  let end = null;
  for (let i = 0; i < buffered.length; i += 1) {
    if (end === null) {
      if (buffered.start(i) <= currentTime + 0.5 && buffered.end(i) >= currentTime) {
        end = buffered.end(i);
      }
    } else if (buffered.start(i) - end <= HOLE_S) {
      end = Math.max(end, buffered.end(i));
    } else {
      break;
    }
  }
  return end === null ? 0 : end - currentTime;
}
