package gregochr.cinefilm.journey;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.MediaFile;
import java.util.List;

/** The runtime of an edition as far as its files say, never as stored on the edition (decision 5). Pure. */
public final class KnownRuntime {

    private KnownRuntime() {
    }

    /**
     * The sum {@link Edition#recomputeRuntime()} would give, worked out from the original parts: each part's
     * timeline duration for a split film, the container duration for one file. Null when the edition is null, has no
     * original part, or any part's duration so read is null or not above 0 (the stored method counts that as 0, which
     * would put the film's end early).
     */
    public static Long knownRuntimeMs(Edition edition) {
        if (edition == null) {
            return null;
        }
        List<MediaFile> parts = edition.originalParts();
        if (parts.isEmpty()) {
            return null;
        }
        boolean split = parts.size() > 1;
        long sum = 0;
        for (MediaFile part : parts) {
            Long duration = split ? part.timelineDurationMs() : part.getDurationMs();
            if (duration == null || duration <= 0) {
                return null;
            }
            sum += duration;
        }
        return sum;
    }
}
