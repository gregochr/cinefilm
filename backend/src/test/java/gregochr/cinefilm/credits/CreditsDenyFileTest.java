package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.credits.CreditsDenyFile.Parsed;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CreditsDenyFileTest {

    private static final String HEADER = "# cinefilm-deny 2026-11-13 ";

    private static Optional<Parsed> parse(String... lines) {
        return CreditsDenyFile.parse(List.of(lines));
    }

    @Test
    void readsTheHeaderTheCountAndAllFourEntryKinds() {
        Parsed parsed = parse(HEADER + "5", "film 12", "unit ctmdb:263", "file 301", "edition 77", "film 13")
            .orElseThrow();

        assertThat(parsed.manifestDate()).isEqualTo("2026-11-13");
        assertThat(parsed.count()).isEqualTo(5);
        assertThat(parsed.list().films()).containsExactlyInAnyOrder(12L, 13L);
        assertThat(parsed.list().units()).containsExactly("ctmdb:263");
        assertThat(parsed.list().files()).containsExactly(301L);
        assertThat(parsed.list().editions()).containsExactly(77L);
    }

    @Test
    void aUnitKeyMayContainSpacesAndBars() {
        Parsed parsed = parse(HEADER + "1", "unit title:the matrix|1999").orElseThrow();

        assertThat(parsed.list().units()).containsExactly("title:the matrix|1999");
    }

    @Test
    void blankLinesAndFurtherCommentLinesAreIgnoredAndNotCounted() {
        Parsed parsed = parse(HEADER + "2", "", "# a comment", "film 1", "   ", "# another", "edition 2").orElseThrow();

        assertThat(parsed.count()).isEqualTo(2);
        assertThat(parsed.list().films()).containsExactly(1L);
        assertThat(parsed.list().editions()).containsExactly(2L);
    }

    @Test
    void aFileWithOnlyAHeaderAndZeroEntriesIsWellFormed() {
        Parsed parsed = parse(HEADER + "0").orElseThrow();

        assertThat(parsed.list().films()).isEmpty();
        assertThat(parsed.list().units()).isEmpty();
        assertThat(parsed.list().files()).isEmpty();
        assertThat(parsed.list().editions()).isEmpty();
    }

    @Test
    void aMissingOrEmptyOrWrongHeaderIsMalformed() {
        assertThat(CreditsDenyFile.parse(List.of())).isEmpty();
        assertThat(parse("film 1")).isEmpty();
        assertThat(parse("# cinefilm-deny 2026-11-13")).isEmpty();
        assertThat(parse("# cinefilm-deny 2026-11-13 x")).isEmpty();
        assertThat(parse("# cinefilm-deny 2026-11-13 -1")).isEmpty();
        assertThat(parse("# cinefilm-deny 2026-11-13 ")).isEmpty();
        assertThat(parse("# cinefilm-deny 2026-11-13 1234567890")).isEmpty();
        assertThat(parse("# cinefilm-deny 2026-11-13 123456789")).isEmpty();
        assertThat(parse("# cinefilm-deny 2026-11-13 1 2", "film 1")).isEmpty();
        assertThat(parse("# cinefilm-deny  1", "film 1")).isEmpty();
        assertThat(parse("#cinefilm-deny 2026-11-13 1", "film 1")).isEmpty();
        assertThat(parse("# something else 2026-11-13 1", "film 1")).isEmpty();
        assertThat(parse("", HEADER + "1", "film 1")).isEmpty();
    }

    @Test
    void aCountThatDiffersFromTheEntriesIsMalformedEitherWay() {
        assertThat(parse(HEADER + "2", "film 1")).isEmpty();
        assertThat(parse(HEADER + "1", "film 1", "film 2")).isEmpty();
        assertThat(parse(HEADER + "1", "film 1")).isPresent();
    }

    @Test
    void aRepeatedEntryStillCountsAsAnEntry() {
        Parsed parsed = parse(HEADER + "2", "film 1", "film 1").orElseThrow();

        assertThat(parsed.list().films()).containsExactly(1L);
    }

    @Test
    void anyOtherLineIsMalformed() {
        assertThat(parse(HEADER + "1", "movie 1")).isEmpty();
        assertThat(parse(HEADER + "1", "film")).isEmpty();
        assertThat(parse(HEADER + "1", "film ")).isEmpty();
        assertThat(parse(HEADER + "1", "film abc")).isEmpty();
        assertThat(parse(HEADER + "1", "file 1x")).isEmpty();
        assertThat(parse(HEADER + "1", "edition -4")).isEmpty();
        assertThat(parse(HEADER + "1", "unit ")).isEmpty();
        assertThat(parse(HEADER + "1", " film 1")).isEmpty();
        assertThat(parse(HEADER + "1", "FILM 1")).isEmpty();
        assertThat(parse(HEADER + "1", "film 1234567890123456789")).isEmpty();
        assertThat(parse(HEADER + "1", "film 1", "garbage")).isEmpty();
    }
}
