package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionTimeline;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Pick;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Slot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What playback serves for an edition, worked out from {@link SidecarPrecedence}'s pick: the version string and the
 * cues on the edition's timeline. The same rules as the WebVTT route ({@code VttWriter.shift} applies the offset), so
 * a check compares what is actually shown.
 */
final class ServedSubtitles {

    private ServedSubtitles() {
    }

    /**
     * The edition's served subtitle.
     *
     * @param edition the edition (its files must be loaded)
     * @param pick what {@link SidecarPrecedence} picked for it (at least one slot)
     * @return the served subtitle, or null when a later part's start on the timeline is unknown
     */
    static ServedSubtitle of(Edition edition, Pick pick) {
        List<Long> partIds = partIds(edition);
        List<Long> starts = starts(edition, pick);
        if (starts == null) {
            return null;
        }
        List<SubtitleCue> cues = new ArrayList<>();
        for (int i = 0; i < pick.slots().size(); i++) {
            SubtitleSidecar sidecar = pick.slots().get(i).sidecar();
            cues.addAll(VttWriter.shift(SrtParser.cues(sidecar.getCuesText()), starts.get(i) + sidecar.getOffsetMs()));
        }
        cues.sort(Comparator.comparingLong(SubtitleCue::startMs).thenComparingLong(SubtitleCue::endMs));
        boolean verified = pick.slots().stream().allMatch(s -> s.sidecar().isVerifiedFor(partIds));
        return new ServedSubtitle(version(partIds, pick, starts), verified, List.copyOf(cues));
    }

    /**
     * The version string of what an edition serves, without reading any cue.
     *
     * @param edition the edition (its files must be loaded)
     * @param pick what {@link SidecarPrecedence} picked for it (at least one slot)
     * @return the version, or null when a later part's start on the timeline is unknown
     */
    static String version(Edition edition, Pick pick) {
        List<Long> starts = starts(edition, pick);
        return starts == null ? null : version(partIds(edition), pick, starts);
    }

    private static String version(List<Long> partIds, Pick pick, List<Long> starts) {
        long updated = pick.slots().stream().mapToLong(s -> s.sidecar().getUpdatedAt().toEpochMilli()).max()
            .orElseThrow();
        StringBuilder version = new StringBuilder("v").append(updated).append('|')
            .append(SubtitleSidecar.partsKey(partIds)).append('|');
        for (int i = 0; i < pick.slots().size(); i++) {
            version.append(i == 0 ? "" : ",").append(pick.slots().get(i).sidecar().getId()).append('@')
                .append(starts.get(i));
        }
        return version.toString();
    }

    /** Where each served file starts on the edition's timeline; null when a later part's start cannot be known. */
    private static List<Long> starts(Edition edition, Pick pick) {
        EditionTimeline timeline = null;
        List<Long> starts = new ArrayList<>();
        for (Slot slot : pick.slots()) {
            long start = 0;
            if (slot.partIndex() > 0) {
                if (timeline == null) {
                    try {
                        timeline = EditionTimeline.of(edition);
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                }
                start = timeline.partStartMs(slot.partIndex());
            }
            starts.add(start);
        }
        return starts;
    }

    private static List<Long> partIds(Edition edition) {
        return edition.originalParts().stream().map(MediaFile::getId).toList();
    }
}
