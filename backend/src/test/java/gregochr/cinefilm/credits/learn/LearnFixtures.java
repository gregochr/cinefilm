package gregochr.cinefilm.credits.learn;

import java.net.URISyntaxException;
import java.nio.file.Path;

/** Where the anonymised fixtures for the labels tool live, and a few builders shared by its tests. */
final class LearnFixtures {

    /** The prefix the fixture archive's paths start with. */
    static final String PREFIX = "/Volumes/Test Media/Movies";

    private LearnFixtures() {
    }

    /** {@code src/test/resources/credits/learn}. */
    static Path dir() {
        try {
            return Path.of(LearnFixtures.class.getResource("/credits/learn").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A credits marker. */
    static ArchiveItem.Marker credits(Long start, Long end) {
        return new ArchiveItem.Marker("credits", start, end, false);
    }

    /** A match for one file: an item of the given duration with one version of the given duration. */
    static ArchiveMatch match(long itemDurationMs, long mediaDurationMs, ArchiveItem.Marker... markers) {
        ArchiveItem.Media media = new ArchiveItem.Media(mediaDurationMs, java.util.List.of(PREFIX + "/A.m4v"));
        return new ArchiveMatch(new ArchiveItem(1, itemDurationMs, java.util.List.of(media),
            java.util.List.of(markers)), media);
    }

    /** An exported file {@code A.m4v}, a last original part, present, with matching fingerprints. */
    static ExportRow row(long durationMs, long plexStartMs) {
        return new ExportRow(1, "A.m4v", "10:20", durationMs, 1, true, 1, 1001L, "A", 2000, null, null, plexStartMs,
            "10:20", "PRESENT");
    }
}
