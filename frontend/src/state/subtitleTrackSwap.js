// Which subtitle <track> of each kind is on screen and which one is still loading after a timing change (8b-1). No JSX
// and no DOM: the player mounts the pending track beside the current one, hidden, and these steps say when it takes over.

/** Nothing pending, no change made yet: the track with the film's own address. */
export const NO_SWAPS = {
  FORCED: { current: null, pending: null },
  FULL: { current: null, pending: null },
};

/** The identity of one mounted subtitle file track: its kind and the token of the change it was made for. */
export function fileTrackKey(kind, token) {
  return `${kind}:${token ?? ''}`;
}

/**
 * One step. `begin`: a change was saved, its token starts loading as the pending track (a newer one replaces an older
 * pending). `commit`: that pending track loaded, so it becomes the current one and the old one goes. `fail`: it did
 * not load, so it is dropped and the current one stays. `settle`: the kind stopped showing, so nothing is waited for.
 * A step for a token that is no longer the pending one does nothing.
 */
export function swapTracks(state, { type, kind, token }) {
  const track = state[kind];
  switch (type) {
    case 'begin':
      return { ...state, [kind]: { ...track, pending: token } };
    case 'commit':
      return track.pending === token
        ? { ...state, [kind]: { current: token, pending: null } }
        : state;
    case 'fail':
      return track.pending === token ? { ...state, [kind]: { ...track, pending: null } } : state;
    case 'settle':
      return track.pending === null
        ? state
        : { ...state, [kind]: { current: track.pending, pending: null } };
    default:
      return state;
  }
}

/**
 * The mode of every mounted track key. Only the current track of the kind that shows is `showing`; its pending one is
 * `hidden` (it loads its cues, shows nothing); every other track is `disabled`, so the browser fetches nothing it does
 * not need. `showing`: the player's own key (`activeSubtitleKey`), a stream key, or a file kind's key.
 */
export function trackModes(keys, { showing, shownKind, swaps }) {
  const current = shownKind ? fileTrackKey(shownKind, swaps[shownKind].current) : null;
  const pending =
    shownKind && swaps[shownKind].pending !== null
      ? fileTrackKey(shownKind, swaps[shownKind].pending)
      : null;
  return new Map(
    keys.map((key) => {
      if (shownKind ? key === current : key === showing) return [key, 'showing'];
      return [key, key === pending ? 'hidden' : 'disabled'];
    }),
  );
}
