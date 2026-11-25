package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Pick;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Slot;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The cues a pick serves, and the same cues as they were before a file was retimed. Pure: no repositories. */
class ServedSubtitlesTest {

    private static final String STORED = "1\n00:00:10,000 --> 00:00:12,000\nstored\n\n";
    private static final String ORIGINAL = "1\n00:01:40,000 --> 00:01:42,000\noriginal\n\n";

    private Edition edition;
    private MediaFile part1;
    private MediaFile part2;

    @BeforeEach
    void setUp() {
        edition = edition(film(1L, "Towers", 2002, NOW), 40L, "Original", 120 * MIN, null);
        part1 = file(edition, "Towers - pt1.m4v");
        part1.setId(10L);
        part1.setPartNumber(1);
        part1.setDurationMs(60 * MIN);
        part2 = file(edition, "Towers - pt2.m4v");
        part2.setId(11L);
        part2.setPartNumber(2);
        part2.setDurationMs(60 * MIN);
    }

    private static SubtitleSidecar sidecar(long id, String cues, String original, int offsetMs) {
        SubtitleSidecar row = new SubtitleSidecar();
        row.setId(id);
        row.setCuesText(cues);
        row.setOriginalCuesText(original);
        row.setOffsetMs(offsetMs);
        row.setUpdatedAt(NOW);
        return row;
    }

    @Test
    void aRetimedFilesOriginalCuesAreMovedByItsOffsetAndItsPartStartLikeTheServedOnes() {
        Pick pick = new Pick(List.of(new Slot(sidecar(5L, STORED, ORIGINAL, 500), 0),
            new Slot(sidecar(6L, STORED, null, 0), 1)), Set.of());

        List<SubtitleCue> original = ServedSubtitles.originalCues(edition, pick);
        List<SubtitleCue> served = ServedSubtitles.of(edition, pick).cues();

        // Part 1 file: its original, 500 ms late. Part 2 file: never retimed, so its stored cues, a part later.
        assertThat(original).containsExactly(new SubtitleCue(100_500, 102_500, "original"),
            new SubtitleCue(60 * MIN + 10_000, 60 * MIN + 12_000, "stored"));
        assertThat(served).containsExactly(new SubtitleCue(10_500, 12_500, "stored"),
            new SubtitleCue(60 * MIN + 10_000, 60 * MIN + 12_000, "stored"));
    }

    @Test
    void whenALaterPartHasNoDurationTheTimelineIsUnknownAndThereAreNoOriginalCues() {
        part1.setDurationMs(null);
        Pick pick = new Pick(List.of(new Slot(sidecar(5L, STORED, ORIGINAL, 0), 0),
            new Slot(sidecar(6L, STORED, null, 0), 1)), Set.of());

        assertThat(ServedSubtitles.originalCues(edition, pick)).isNull();
    }
}
