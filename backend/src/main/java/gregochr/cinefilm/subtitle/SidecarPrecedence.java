package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.film.Presence;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which of the owner's forced subtitle files an edition would play (a download is never in this pick:
 * {@link ForcedChoice} chooses between the owner's files, downloads and the embedded track). Only files that are
 * present and have at least one cue compete. One whole-edition file beats per-part files; two whole-edition files are
 * a conflict and nothing plays. Otherwise each part plays its own file, and two files for one part are a conflict that
 * leaves that part with none. Pure.
 */
final class SidecarPrecedence {

    /** A file to play and which part's start it is timed from (0 for a whole-edition file). */
    record Slot(SubtitleSidecar sidecar, int partIndex) {
    }

    /**
     * The outcome for one edition.
     *
     * @param slots the files to play, in part order; empty when none plays
     * @param conflicting the ids of the files that compete for the same place
     */
    record Pick(List<Slot> slots, Set<Long> conflicting) {
    }

    private SidecarPrecedence() {
    }

    /**
     * Picks what an edition plays.
     *
     * @param partIds the ids of the edition's part files in play order
     * @param sidecars the files attached to the edition's parts (others, and downloads, are ignored)
     * @return the files to play and the ones in conflict
     */
    static Pick pick(List<Long> partIds, List<SubtitleSidecar> sidecars) {
        List<SubtitleSidecar> playable = sidecars.stream()
            .filter(s -> !s.isDownload() && s.getMediaFileId() != null && partIds.contains(s.getMediaFileId())
                && s.getPresence() == Presence.PRESENT
                && s.getCueCount() > 0)
            .toList();
        List<SubtitleSidecar> wholes = playable.stream().filter(SubtitleSidecar::isWholeEdition).toList();
        if (wholes.size() == 1) {
            return new Pick(List.of(new Slot(wholes.get(0), 0)), Set.of());
        }
        if (wholes.size() > 1) {
            return new Pick(List.of(), ids(wholes));
        }
        List<Slot> slots = new ArrayList<>();
        Set<Long> conflicting = new LinkedHashSet<>();
        for (int i = 0; i < partIds.size(); i++) {
            Long partId = partIds.get(i);
            List<SubtitleSidecar> forPart = playable.stream().filter(s -> partId.equals(s.getMediaFileId())).toList();
            if (forPart.size() == 1) {
                slots.add(new Slot(forPart.get(0), i));
            } else if (forPart.size() > 1) {
                conflicting.addAll(ids(forPart));
            }
        }
        return new Pick(List.copyOf(slots), conflicting);
    }

    private static Set<Long> ids(List<SubtitleSidecar> sidecars) {
        Set<Long> ids = new LinkedHashSet<>();
        sidecars.forEach(s -> ids.add(s.getId()));
        return ids;
    }
}
