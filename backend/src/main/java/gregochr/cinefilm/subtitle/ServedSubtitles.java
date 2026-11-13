package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionTimeline;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Pick;
import gregochr.cinefilm.web.JsonEtag;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Slot;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
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
     * What the WebVTT route serves for a pick: each file's cues moved by its part's start and the owner's offset
     * together (so a cue shifted earlier than its part's start lands on the edition's timeline, not clipped at the
     * part's own 0), the text escaped, and the parts joined into one file. Used for forced and full files alike.
     *
     * @param edition the edition (its files must be loaded)
     * @param pick what {@link SidecarPrecedence} picked for it (at least one slot)
     * @param unavailable the message of the 404 when a later part's start on the timeline is unknown
     * @return the WebVTT text
     * @throws NotFoundException when the timeline of a split edition is unknown
     */
    static String vtt(Edition edition, Pick pick, String unavailable) {
        List<String> texts = new ArrayList<>();
        EditionTimeline timeline = null;
        for (Slot slot : pick.slots()) {
            long start = 0;
            if (slot.partIndex() > 0) {
                if (timeline == null) {
                    try {
                        timeline = EditionTimeline.of(edition);
                    } catch (IllegalArgumentException e) {
                        throw new NotFoundException(unavailable);
                    }
                }
                start = timeline.partStartMs(slot.partIndex());
            }
            texts.add(VttWriter.write(SrtParser.cues(slot.sidecar().getCuesText()),
                start + slot.sidecar().getOffsetMs()));
        }
        return texts.size() == 1 ? texts.get(0) : VttShifter.join(texts, Collections.nCopies(texts.size(), 0L));
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

    /**
     * Built from each served file's own state, in serve order: {@code id@start/offset/fingerprint}, where the
     * fingerprint is the first 16 hex characters of the SHA-256 of its cues (hashing a few small files per call is
     * cheap). No timestamp is used, so two changes in the same millisecond cannot leave it unchanged.
     */
    private static String version(List<Long> partIds, Pick pick, List<Long> starts) {
        StringBuilder version = new StringBuilder("p").append(SubtitleSidecar.partsKey(partIds)).append('|');
        for (int i = 0; i < pick.slots().size(); i++) {
            SubtitleSidecar sidecar = pick.slots().get(i).sidecar();
            String tag = JsonEtag.etag(sidecar.getCuesText().getBytes(StandardCharsets.UTF_8));
            version.append(i == 0 ? "" : ",").append(sidecar.getId()).append('@').append(starts.get(i)).append('/')
                .append(sidecar.getOffsetMs()).append('/').append(tag, 3, 3 + 16);
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
