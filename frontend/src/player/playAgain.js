// What a replacement viewing ("Play again" after Admin stopped the playback) is mounted from. Pure: no DOM, no React.

/** How long Play again waits for the film to be read again before it mounts from what it knows. */
export const REFRESH_MS = 4000;

/**
 * The film with the subtitle timing this page has since saved, for when the film cannot be read again: each edition's
 * forced and full subtitle whose rows (`sidecarIds`) are the ones the timing was saved for gets those offsets. Nothing
 * else changes; `learned` is `{ FORCED?: { ids, offsets }, FULL?: { ids, offsets } }`.
 */
export function withLearnedOffsets(film, learned) {
  const patch = (subtitle, entry) =>
    subtitle &&
    entry &&
    entry.ids.length === subtitle.sidecarIds?.length &&
    entry.ids.every((id, i) => id === subtitle.sidecarIds[i])
      ? { ...subtitle, offsetsMs: entry.offsets }
      : subtitle;
  return {
    ...film,
    editions: film.editions.map((edition) => ({
      ...edition,
      forcedSubtitle: patch(edition.forcedSubtitle, learned.FORCED),
      fullSubtitle: patch(edition.fullSubtitle, learned.FULL),
    })),
  };
}
