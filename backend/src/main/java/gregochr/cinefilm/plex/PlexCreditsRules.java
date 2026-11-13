package gregochr.cinefilm.plex;

import gregochr.cinefilm.credits.EditionGuard;
import gregochr.cinefilm.plex.PlexModels.PlexItemMarkers;
import gregochr.cinefilm.plex.PlexModels.PlexMarker;
import gregochr.cinefilm.plex.PlexModels.PlexMediaTiming;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The credits import's pure decisions: whether Plex's marker for an item can be trusted at all, and where it lands in
 * each CineFilm copy. Plex keeps one marker per item but the item can hold several copies of different lengths, and
 * does not say which copy it measured, so the shortest is assumed: that can only move the marker later, never earlier.
 * Every number it compares comes from Plex or from CineFilm's own probe, never from the marker's own side.
 */
final class PlexCreditsRules {

    /** Two lengths within this many milliseconds are the same film. */
    static final long TOLERANCE_MS = 2_000;
    /** The type of Plex marker that holds the credits. */
    static final String CREDITS = "credits";

    private PlexCreditsRules() {
    }

    /**
     * One CineFilm file that pairs with the Plex item.
     *
     * @param mediaFileId the file
     * @param path its path relative to the library root
     * @param fingerprint its size and modification time as last scanned, or null when unknown
     * @param durationMs its length on its timeline, or null when unknown
     */
    record Copy(long mediaFileId, String path, String fingerprint, Long durationMs) {
    }

    /**
     * A marker to store.
     *
     * @param mediaFileId the file
     * @param fingerprint the file's fingerprint now
     * @param startMs where the credits start inside the file
     */
    record Row(long mediaFileId, String fingerprint, long startMs) {
    }

    /**
     * What the rules decided for one item.
     *
     * @param rows the markers to store, in the copies' order
     * @param skipped how many films or files were skipped, by reason (only reasons that happened)
     * @param unmatched copies that match none of the item's versions
     */
    record Decision(List<Row> rows, Map<PlexCreditsSkip, Integer> skipped, int unmatched) {
    }

    /**
     * Decides what to store for one Plex item.
     *
     * @param item the item as Plex describes it now
     * @param copies the CineFilm files the item's list entry paired with
     * @param prefix the Plex path prefix, as {@link PlexRules#prefix(String)}
     * @return the rows, the skips and the copies that match no version
     */
    static Decision decide(PlexItemMarkers item, List<Copy> copies, String prefix) {
        Map<PlexCreditsSkip, Integer> skipped = new EnumMap<>(PlexCreditsSkip.class);
        PlexCreditsSkip itemSkip = itemSkip(item);
        Long marker = itemSkip == null ? earliestCredits(item) : null;
        if (itemSkip == null && marker == null) {
            itemSkip = PlexCreditsSkip.NO_CREDITS_MARKER;
        }
        if (itemSkip == null && !EditionGuard.plausible(marker, item.durationMs())) {
            itemSkip = PlexCreditsSkip.MARKER_IMPLAUSIBLE;
        }
        if (itemSkip != null) {
            skipped.put(itemSkip, 1);
            return new Decision(List.of(), skipped, 0);
        }
        long shortest = item.durationMs();
        for (PlexMediaTiming media : item.media()) {
            shortest = Math.min(shortest, media.durationMs());
        }
        List<Row> rows = new ArrayList<>();
        int unmatched = 0;
        for (Copy copy : copies) {
            Optional<PlexMediaTiming> media = matching(item, copy, prefix);
            if (media.isEmpty()) {
                unmatched++;
                continue;
            }
            long shift = Math.max(0, media.get().durationMs() - shortest);
            PlexCreditsSkip copySkip = copySkip(copy, media.get(), marker + shift);
            if (copySkip == null) {
                rows.add(new Row(copy.mediaFileId(), copy.fingerprint(), marker + shift));
            } else {
                skipped.merge(copySkip, 1, Integer::sum);
            }
        }
        return new Decision(List.copyOf(rows), skipped, unmatched);
    }

    /** Why the whole item is skipped before any marker is looked at, or null when it is not. */
    private static PlexCreditsSkip itemSkip(PlexItemMarkers item) {
        if (item.media().isEmpty() || item.media().stream().anyMatch(m -> m.parts().isEmpty())) {
            return PlexCreditsSkip.NO_MEDIA;
        }
        if (item.media().stream().anyMatch(m -> m.parts().size() != 1)) {
            return PlexCreditsSkip.MULTI_PART;
        }
        if (item.durationMs() == null || item.durationMs() <= 0) {
            return PlexCreditsSkip.ITEM_NO_DURATION;
        }
        if (item.media().stream().anyMatch(m -> m.durationMs() == null || m.durationMs() <= 0)) {
            return PlexCreditsSkip.MEDIA_NO_DURATION;
        }
        if (item.media().stream().anyMatch(m -> Math.abs(m.durationMs() - item.durationMs()) > TOLERANCE_MS)) {
            return PlexCreditsSkip.DURATION_MISMATCH;
        }
        return null;
    }

    /** The lowest start of any credits marker, final or not, whatever order Plex lists them in; null when none. */
    private static Long earliestCredits(PlexItemMarkers item) {
        return item.markers().stream().filter(m -> CREDITS.equals(m.type())).map(PlexMarker::startMs)
            .min(Long::compare).orElse(null);
    }

    /** The version whose file is this copy, by path (never by position), or empty when Plex lists none. */
    private static Optional<PlexMediaTiming> matching(PlexItemMarkers item, Copy copy, String prefix) {
        String path = PlexRules.nfc(copy.path());
        return item.media().stream()
            .filter(m -> PlexRules.relativePath(prefix, m.parts().get(0)).filter(path::equals).isPresent())
            .findFirst();
    }

    /** Why this copy is skipped, or null when its moved marker can be stored. */
    private static PlexCreditsSkip copySkip(Copy copy, PlexMediaTiming media, long startMs) {
        if (copy.fingerprint() == null) {
            return PlexCreditsSkip.NO_FINGERPRINT;
        }
        if (copy.durationMs() == null || copy.durationMs() <= 0) {
            return PlexCreditsSkip.LOCAL_DURATION_UNKNOWN;
        }
        if (Math.abs(copy.durationMs() - media.durationMs()) > TOLERANCE_MS) {
            return PlexCreditsSkip.LOCAL_DURATION_DIFFERS;
        }
        return EditionGuard.plausible(startMs, copy.durationMs()) ? null : PlexCreditsSkip.SHIFTED_IMPLAUSIBLE;
    }
}
