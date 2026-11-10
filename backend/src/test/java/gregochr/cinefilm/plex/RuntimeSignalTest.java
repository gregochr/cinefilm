package gregochr.cinefilm.plex;

import static gregochr.cinefilm.TestData.MIN;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.TestData;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The 15% runtime signal of the different-film guard. */
class RuntimeSignalTest {

    private static Edition edition(long id, long minutes, String... paths) {
        Film film = TestData.film(1L, "Heat", 1995, Instant.EPOCH);
        Edition edition = TestData.edition(film, id, "Original", minutes * MIN, null);
        for (String path : paths) {
            TestData.file(edition, path).setDurationMs(minutes * MIN / paths.length);
        }
        return edition;
    }

    @Test
    void exactlyFifteenPercentIsTheSameFilmAndOneMinuteMoreIsNot() {
        assertThat(RuntimeSignal.differs(100 * MIN, 85 * MIN)).isFalse();
        assertThat(RuntimeSignal.differs(100 * MIN, 85 * MIN + 1)).isFalse();
        assertThat(RuntimeSignal.differs(100 * MIN, 85 * MIN - 1)).isTrue();
        assertThat(RuntimeSignal.differs(85 * MIN - 1, 100 * MIN)).isTrue();
        assertThat(RuntimeSignal.differs(0, 100 * MIN)).isFalse();
        assertThat(RuntimeSignal.differs(100 * MIN, 0)).isFalse();
    }

    @Test
    void theReportLineSaysHowFarApartAndWhichFiles() {
        assertThat(RuntimeSignal.describe(100 * MIN, 130 * MIN, "a/Heat.m4v", "Heat HD.m4v")).isEqualTo(
            "Runtimes differ by 23% — check these are the same film: \"Heat HD.m4v\" and \"Heat.m4v\"");
        assertThat(RuntimeSignal.describe(130 * MIN, 100 * MIN, "Heat HD.m4v", "a/Heat.m4v")).isEqualTo(
            RuntimeSignal.describe(100 * MIN, 130 * MIN, "a/Heat.m4v", "Heat HD.m4v"));
    }

    @Test
    void editionWordsAndTagsMakeAFileTagged() {
        assertThat(RuntimeSignal.untagged(List.of("Heat.m4v", "Heat HD.m4v"))).isTrue();
        assertThat(RuntimeSignal.untagged(List.of("Heat {edition-Cut}.m4v"))).isFalse();
        assertThat(RuntimeSignal.untagged(List.of("Heat.m4v", "Heat (Director's Cut).m4v"))).isFalse();
    }

    @Test
    void untaggedEditionsFarApartConflictAndEverythingElseDoesNot() {
        Edition a = edition(1, 100, "Heat.m4v");
        Edition far = edition(2, 130, "Heat HD.m4v");
        Edition near = edition(3, 105, "Heat 2.m4v");
        Edition tagged = edition(4, 130, "Heat {edition-Long}.m4v");
        Edition lonePart = edition(5, 50, "Heat - pt1.m4v");
        Edition joined = edition(6, 100, "Heat - pt1.m4v", "Heat - pt2.m4v");

        assertThat(RuntimeSignal.conflict(List.of(a), List.of(far))).startsWith("Runtimes differ by 23%");
        assertThat(RuntimeSignal.conflict(List.of(a), List.of(near))).isNull();
        assertThat(RuntimeSignal.conflict(List.of(a), List.of(tagged))).isNull();
        assertThat(RuntimeSignal.conflict(List.of(a), List.of(lonePart))).isNull();
        assertThat(RuntimeSignal.conflict(List.of(a), List.of(joined))).isNull();
        assertThat(RuntimeSignal.conflict(List.of(a), List.of(a))).isNull();
        assertThat(RuntimeSignal.conflict(List.of(joined), List.of(far))).startsWith("Runtimes differ");
        assertThat(RuntimeSignal.conflict(List.of(a), List.of(edition(7, 130)))).isNull();
    }
}
