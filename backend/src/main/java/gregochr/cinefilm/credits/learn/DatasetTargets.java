package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.CreditsFrameReader;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.plex.PlexCreditsMarker;
import gregochr.cinefilm.plex.PlexCreditsMarkerRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database side of the credits dataset job: which files to read, from the Plex marker rows. A marker is used only
 * while it still describes the file: the file is present and playable, has the marker's fingerprint and a duration, is
 * the last original part of its edition (the only part the credits analysis reads), and the marker lies inside the
 * window of the file's end. Two queries however many films there are; nothing is written.
 */
@Service
@Transactional(readOnly = true)
public class DatasetTargets {

    private static final Logger LOG = LoggerFactory.getLogger(DatasetTargets.class);

    /** Why a marker was not used. */
    private enum LeftOut {
        NO_FILE("its file is not in the library"),
        NOT_LAST_PART("its file is not the last part of its edition"),
        NOT_READABLE("its file is missing or not playable"),
        CHANGED("its file changed since the import, or has no fingerprint"),
        NO_DURATION("its file has no duration"),
        OUTSIDE_WINDOW("it is outside the window of the file's end");

        private final String text;

        LeftOut(String text) {
            this.text = text;
        }
    }

    private final PlexCreditsMarkerRepository markers;
    private final EditionRepository editions;
    private final MediaFileRepository files;

    /** Creates the service. */
    public DatasetTargets(PlexCreditsMarkerRepository markers, EditionRepository editions,
                          MediaFileRepository files) {
        this.markers = markers;
        this.editions = editions;
        this.files = files;
    }

    /**
     * Every marker whose file can be read for the dataset, in file id order.
     *
     * @return the targets
     */
    public List<DatasetTarget> targets() {
        Map<Long, MediaFile> originals = new HashMap<>();
        Set<Long> lastParts = new HashSet<>();
        for (Edition edition : editions.findAllWithFiles()) {
            List<MediaFile> parts = edition.originalParts();
            for (MediaFile part : parts) {
                originals.put(part.getId(), part);
            }
            if (!parts.isEmpty()) {
                lastParts.add(parts.getLast().getId());
            }
        }
        Map<LeftOut, Integer> leftOut = new EnumMap<>(LeftOut.class);
        List<DatasetTarget> targets = new ArrayList<>();
        for (PlexCreditsMarker marker : markers.findAll().stream()
            .sorted(Comparator.comparing(PlexCreditsMarker::getMediaFileId)).toList()) {
            MediaFile file = originals.get(marker.getMediaFileId());
            LeftOut reason = reasonToLeaveOut(marker, file, lastParts);
            if (reason != null) {
                leftOut.merge(reason, 1, Integer::sum);
            } else {
                targets.add(new DatasetTarget(file.getId(), file.getPath(), file.fingerprint(),
                    file.timelineDurationMs(), marker.getStartMs()));
            }
        }
        LOG.info("Credits dataset targets: {} files; markers left out: {}", targets.size(), describe(leftOut));
        return targets;
    }

    /**
     * Whether the file is still the one the target was made from: present, with the same fingerprint.
     *
     * @param target the target
     * @return true when the file still has the target's fingerprint and is present
     */
    public boolean stillCurrent(DatasetTarget target) {
        return files.findById(target.mediaFileId())
            .filter(file -> file.getPresence() == Presence.PRESENT)
            .filter(file -> target.fingerprint().equals(file.fingerprint()))
            .isPresent();
    }

    private static LeftOut reasonToLeaveOut(PlexCreditsMarker marker, MediaFile file, Set<Long> lastParts) {
        if (file == null) {
            return LeftOut.NO_FILE;
        }
        if (!lastParts.contains(file.getId())) {
            return LeftOut.NOT_LAST_PART;
        }
        if (file.getPresence() != Presence.PRESENT || file.getPlayability() != Playability.PLAYABLE) {
            return LeftOut.NOT_READABLE;
        }
        if (file.fingerprint() == null || !file.fingerprint().equals(marker.getFingerprint())) {
            return LeftOut.CHANGED;
        }
        Long duration = file.timelineDurationMs();
        if (duration == null || duration <= 0) {
            return LeftOut.NO_DURATION;
        }
        long windowStart = duration - CreditsFrameReader.windowMs(duration);
        if (marker.getStartMs() < windowStart || marker.getStartMs() >= duration) {
            return LeftOut.OUTSIDE_WINDOW;
        }
        return null;
    }

    private static String describe(Map<LeftOut, Integer> leftOut) {
        if (leftOut.isEmpty()) {
            return "none";
        }
        StringBuilder text = new StringBuilder();
        leftOut.forEach((reason, count) -> {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(count).append(" because ").append(reason.text);
        });
        return text.toString();
    }
}
