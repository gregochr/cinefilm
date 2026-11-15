import { describe, expect, it } from 'vitest';
import { NO_SWAPS, fileTrackKey, swapTracks, trackModes } from './subtitleTrackSwap.js';

const pending = (token, current = null) => ({
  ...NO_SWAPS,
  FULL: { current, pending: token },
});

describe('fileTrackKey', () => {
  it('names the kind and the change a track was made for; the film’s own track has no change', () => {
    expect(fileTrackKey('FULL', 'a')).toBe('FULL:a');
    expect(fileTrackKey('FULL', null)).toBe('FULL:');
  });
});

describe('swapTracks', () => {
  it('begin makes the token pending and leaves the current track alone', () => {
    expect(
      swapTracks(pending(null, 'a'), { type: 'begin', kind: 'FULL', token: 'b' }).FULL,
    ).toEqual({ current: 'a', pending: 'b' });
  });

  it('a newer begin replaces an older pending one', () => {
    expect(swapTracks(pending('b'), { type: 'begin', kind: 'FULL', token: 'c' }).FULL.pending).toBe(
      'c',
    );
  });

  it('commit makes the pending track current, and the old one goes', () => {
    expect(
      swapTracks(pending('b', 'a'), { type: 'commit', kind: 'FULL', token: 'b' }).FULL,
    ).toEqual({ current: 'b', pending: null });
  });

  it('commit and fail for a token that is no longer pending do nothing', () => {
    const state = pending('c', 'a');

    expect(swapTracks(state, { type: 'commit', kind: 'FULL', token: 'b' })).toBe(state);
    expect(swapTracks(state, { type: 'fail', kind: 'FULL', token: 'b' })).toBe(state);
  });

  it('fail drops the pending track and keeps the current one', () => {
    expect(swapTracks(pending('b', 'a'), { type: 'fail', kind: 'FULL', token: 'b' }).FULL).toEqual({
      current: 'a',
      pending: null,
    });
  });

  it('settle takes the pending track over without waiting, and does nothing when nothing is pending', () => {
    expect(swapTracks(pending('b', 'a'), { type: 'settle', kind: 'FULL' }).FULL).toEqual({
      current: 'b',
      pending: null,
    });
    expect(swapTracks(NO_SWAPS, { type: 'settle', kind: 'FULL' })).toBe(NO_SWAPS);
  });

  it('leaves the other kind alone and ignores an unknown step', () => {
    const next = swapTracks(NO_SWAPS, { type: 'begin', kind: 'FORCED', token: 'x' });

    expect(next.FULL).toBe(NO_SWAPS.FULL);
    expect(swapTracks(next, { type: 'nope', kind: 'FORCED' })).toBe(next);
  });
});

describe('trackModes', () => {
  const keys = [3, 'FORCED:', 'FULL:a', 'FULL:b'];
  const swaps = pending('b', 'a');

  it('shows only the current track of the shown kind and loads its pending one hidden', () => {
    const modes = trackModes(keys, { showing: 'full', shownKind: 'FULL', swaps });

    expect([...modes]).toEqual([
      [3, 'disabled'],
      ['FORCED:', 'disabled'],
      ['FULL:a', 'showing'],
      ['FULL:b', 'hidden'],
    ]);
  });

  it('keeps a pending track disabled when its kind is not the one showing', () => {
    const modes = trackModes(keys, { showing: 'forced', shownKind: 'FORCED', swaps });

    expect(modes.get('FORCED:')).toBe('showing');
    expect(modes.get('FULL:a')).toBe('disabled');
    expect(modes.get('FULL:b')).toBe('disabled');
  });

  it('shows a stream by its own key when no file kind shows', () => {
    const modes = trackModes(keys, { showing: 3, shownKind: null, swaps });

    expect(modes.get(3)).toBe('showing');
    expect(modes.get('FULL:b')).toBe('disabled');
  });
});
