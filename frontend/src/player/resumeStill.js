// The resume still: the frame at the resume point, shown behind the startup indicator until the film plays.
// A small state machine so the show/hide rules live (and are tested) outside the component.
//
// phase: 'none'    not used (the film starts at 0) or failed to load: nothing is rendered
//        'pending' the <img> is rendered but invisible, waiting for the picture to load
//        'shown'   loaded and visible
//        'hiding'  fading out, about to be removed
//        'gone'    removed for good: a late load is ignored and it is never brought back

export const STILL_FADE_MS = 200;

/** The phase to start in: a still only exists when resuming (a start position above 0). */
export const initialStill = (resumeMs) => (resumeMs > 0 ? 'pending' : 'none');

/** Events that end the still for good: the film plays, the viewer seeks, or the player has no film to show. */
export const STILL_HIDE_EVENTS = ['playing', 'seek', 'unavailable', 'ended'];

/** The next phase for an event: `loaded`, `errored`, `faded` or one of {@link STILL_HIDE_EVENTS}. */
export function stillReducer(phase, event) {
  if (STILL_HIDE_EVENTS.includes(event)) {
    if (phase === 'shown') return 'hiding';
    // Never shown (still loading): drop it at once; a picture that arrives later must not flash.
    return phase === 'pending' ? 'gone' : phase;
  }
  if (event === 'loaded') return phase === 'pending' ? 'shown' : phase;
  if (event === 'errored') return phase === 'pending' || phase === 'shown' ? 'none' : phase;
  if (event === 'faded') return phase === 'hiding' ? 'gone' : phase;
  return phase;
}

/** Whether the <img> is in the page. */
export const stillRendered = (phase) =>
  phase === 'pending' || phase === 'shown' || phase === 'hiding';
