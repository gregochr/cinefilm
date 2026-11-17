package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.credits.CreditsDetection;
import gregochr.cinefilm.credits.CreditsDetectionRepository;
import gregochr.cinefilm.credits.CreditsVerdict;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionTimeline;
import gregochr.cinefilm.film.MediaFile;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Where credits detection found an edition's credits start, on the edition's own timeline. */
final class CreditsStarts {

    private CreditsStarts() {
    }

    /**
     * The credits start on the edition's timeline.
     *
     * @param edition the edition (its files loaded, at least one original part)
     * @param credits where the detections are read from (one per media file)
     * @return the milliseconds, or null when detection found none or a part's length is unknown
     */
    static Long of(Edition edition, CreditsDetectionRepository credits) {
        List<MediaFile> parts = edition.originalParts();
        return place(edition, credits.findById(parts.get(parts.size() - 1).getId()).orElse(null));
    }

    /**
     * The credits start of each of some editions, in one query.
     *
     * @param editions the editions (their files loaded, each with at least one original part)
     * @param credits where the detections are read from
     * @return the credits start by edition id; an edition with none (or none placeable) is absent
     */
    static Map<Long, Long> ofAll(Collection<Edition> editions, CreditsDetectionRepository credits) {
        Map<Long, CreditsDetection> byFile = new HashMap<>();
        credits.findAllById(editions.stream().map(e -> e.originalParts().get(e.originalParts().size() - 1).getId())
            .toList()).forEach(d -> byFile.put(d.getMediaFileId(), d));
        Map<Long, Long> starts = new HashMap<>();
        for (Edition edition : editions) {
            List<MediaFile> parts = edition.originalParts();
            Long start = place(edition, byFile.get(parts.get(parts.size() - 1).getId()));
            if (start != null) {
                starts.put(edition.getId(), start);
            }
        }
        return starts;
    }

    private static Long place(Edition edition, CreditsDetection detection) {
        int last = edition.originalParts().size() - 1;
        if (detection == null || detection.getVerdict() != CreditsVerdict.FOUND || detection.getOffsetMs() == null) {
            return null;
        }
        if (last == 0) {
            return detection.getOffsetMs();
        }
        try {
            return EditionTimeline.of(edition).partStartMs(last) + detection.getOffsetMs();
        } catch (IllegalArgumentException e) {
            // A part's length is not known: the credits start cannot be placed on the edition's timeline.
            return null;
        }
    }
}
