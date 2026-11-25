package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.transcode.MediaKeyframes;
import gregochr.cinefilm.transcode.MediaKeyframesRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The frame rate of a film file, from its keyframe index ({@code media_keyframes.fps}), for the rate rule
 * ({@link RateComparison}). A rate counts only while the index still describes the file: its {@code source_size} is the
 * file's {@code size_bytes}. A whole split edition has a rate only when every part has a current one and they are the
 * same; otherwise it is unknown (null). Read on demand and never cached: callers ask only when something is retimed.
 */
@Service
public class FilmRates {

    private final EditionRepository editions;
    private final MediaFileRepository files;
    private final MediaKeyframesRepository keyframes;

    /** Creates the reader. */
    public FilmRates(EditionRepository editions, MediaFileRepository files, MediaKeyframesRepository keyframes) {
        this.editions = editions;
        this.files = files;
        this.keyframes = keyframes;
    }

    /**
     * The rate of a whole edition: its original parts' common rate.
     *
     * @param editionId the edition
     * @return the rate, or null when the edition or a part has no current rate, or the parts differ
     */
    public Double forEdition(Long editionId) {
        return forEditions(Set.of(editionId)).get(editionId);
    }

    /**
     * The rates of several editions at once, in two reads however many there are (the Admin list).
     *
     * @param editionIds the editions
     * @return each edition's rate by its id; an edition that is unknown or has no current common rate is left out
     */
    public Map<Long, Double> forEditions(Collection<Long> editionIds) {
        if (editionIds.isEmpty()) {
            return Map.of();
        }
        List<Edition> found = editions.findWithFilesByIdIn(Set.copyOf(editionIds));
        Map<Long, MediaKeyframes> indexes = indexes(found.stream().flatMap(e -> e.originalParts().stream()).toList());
        Map<Long, Double> rates = new HashMap<>();
        for (Edition edition : found) {
            Double rate = common(edition.originalParts(), indexes);
            if (rate != null) {
                rates.put(edition.getId(), rate);
            }
        }
        return rates;
    }

    private static Double common(List<MediaFile> parts, Map<Long, MediaKeyframes> indexes) {
        Double common = null;
        for (MediaFile part : parts) {
            Double rate = current(part, indexes.get(part.getId()));
            if (rate == null || common != null && !FrameRateRetime.sameRate(common, rate)) {
                return null;
            }
            common = common == null ? rate : common;
        }
        return common;
    }

    /**
     * The rate of one film file (a subtitle timed for one part).
     *
     * @param mediaFileId the film file
     * @return the rate, or null when it has no current one
     */
    public Double forFile(Long mediaFileId) {
        MediaFile file = files.findById(mediaFileId).orElse(null);
        if (file == null) {
            return null;
        }
        return current(file, keyframes.findById(mediaFileId).orElse(null));
    }

    private Map<Long, MediaKeyframes> indexes(List<MediaFile> parts) {
        Map<Long, MediaKeyframes> indexes = new HashMap<>();
        if (parts.isEmpty()) {
            return indexes;
        }
        keyframes.findAllById(parts.stream().map(MediaFile::getId).toList())
            .forEach(k -> indexes.put(k.getMediaFileId(), k));
        return indexes;
    }

    private static Double current(MediaFile file, MediaKeyframes index) {
        if (index == null || index.getFps() == null || file.getSizeBytes() == null
            || index.getSourceSize() != file.getSizeBytes()) {
            return null;
        }
        return index.getFps();
    }
}
