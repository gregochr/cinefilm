package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.credits.CreditsDetection;
import gregochr.cinefilm.credits.CreditsDetectionRepository;
import gregochr.cinefilm.credits.CreditsVerdict;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionTimeline;
import gregochr.cinefilm.film.MediaFile;
import java.util.List;

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
        int last = parts.size() - 1;
        CreditsDetection detection = credits.findById(parts.get(last).getId()).orElse(null);
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
