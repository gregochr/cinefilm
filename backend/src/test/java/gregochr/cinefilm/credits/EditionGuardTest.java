package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.credits.EditionGuard.EditionVerdict;
import gregochr.cinefilm.film.EditionTimeline;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditionGuardTest {

    /** Parts of 4,000,000 and 3,200,000 ms: 7,200,000 in all; 60% is 4,320,000; 60 s from the end is 7,140,000. */
    private static final EditionTimeline TWO_PARTS = EditionTimeline.of(List.of(4_000_000L, 3_200_000L));

    private static EditionVerdict guard(long offsetInLastPart) {
        return EditionGuard.apply(CreditsResult.found(offsetInLastPart, "found"), TWO_PARTS, 1);
    }

    @Test
    void theMarkerOfATwoPartEditionLandsInPartTwoOnTheEditionTimeline() {
        EditionVerdict verdict = guard(1_800_000);

        assertThat(verdict.verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(verdict.markerMs()).isEqualTo(5_800_000L);
        assertThat(verdict.reasons()).containsExactly("found");
    }

    @Test
    void aSinglePartEditionIsItsOwnTimeline() {
        EditionTimeline single = EditionTimeline.of(List.of(7_200_000L));

        EditionVerdict verdict = EditionGuard.apply(CreditsResult.found(6_000_000, "found"), single, 0);

        assertThat(verdict.verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(verdict.markerMs()).isEqualTo(6_000_000L);
    }

    @Test
    void sixtyPercentIntoTheEditionIsTheEarliestAllowed() {
        assertThat(guard(320_000).verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(guard(320_000).markerMs()).isEqualTo(4_320_000L);

        EditionVerdict early = guard(319_999);
        assertThat(early.verdict()).isEqualTo(CreditsVerdict.UNSURE);
        assertThat(early.markerMs()).isEqualTo(4_319_999L);
        assertThat(early.reasons()).containsExactly("found",
            "The credits would start only 59% into the film, and they should start at least 60% in");
    }

    @Test
    void sixtySecondsBeforeTheEndIsTheLatestAllowed() {
        assertThat(guard(3_140_000).verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(guard(3_140_000).markerMs()).isEqualTo(7_140_000L);

        EditionVerdict late = guard(3_140_001);
        assertThat(late.verdict()).isEqualTo(CreditsVerdict.UNSURE);
        assertThat(late.markerMs()).isEqualTo(7_140_001L);
        assertThat(late.reasons()).containsExactly("found",
            "The credits would start less than a minute before the end of the film");
    }

    @Test
    void aMarkerBrokenByBothLimitsGetsBothReasons() {
        EditionTimeline shortEdition = EditionTimeline.of(List.of(100_000L));

        EditionVerdict verdict = EditionGuard.apply(CreditsResult.found(50_000, "found"), shortEdition, 0);

        assertThat(verdict.verdict()).isEqualTo(CreditsVerdict.UNSURE);
        assertThat(verdict.reasons()).hasSize(3);
    }

    @Test
    void anOffsetOutsideThePartIsUnsureWithNoMarker() {
        EditionVerdict verdict = guard(3_200_001);

        assertThat(verdict.verdict()).isEqualTo(CreditsVerdict.UNSURE);
        assertThat(verdict.markerMs()).isNull();
        assertThat(verdict.reasons())
            .containsExactly("found", "The detected time lies outside the part it was found in");
    }

    @Test
    void anUnsureResultKeepsItsVerdictAndGetsAMarker() {
        EditionVerdict verdict = EditionGuard.apply(CreditsResult.unsure(500_000L, "doubt"), TWO_PARTS, 1);

        assertThat(verdict.verdict()).isEqualTo(CreditsVerdict.UNSURE);
        assertThat(verdict.markerMs()).isEqualTo(4_500_000L);
        assertThat(verdict.reasons()).containsExactly("doubt");
    }

    @Test
    void aResultWithNoOffsetPassesThrough() {
        EditionVerdict notFound = EditionGuard.apply(CreditsResult.notFound("none"), TWO_PARTS, 1);
        EditionVerdict unknown = EditionGuard.apply(CreditsResult.unsure(null, "dunno"), TWO_PARTS, 1);

        assertThat(notFound.verdict()).isEqualTo(CreditsVerdict.NOT_FOUND);
        assertThat(notFound.markerMs()).isNull();
        assertThat(notFound.reasons()).containsExactly("none");
        assertThat(unknown.verdict()).isEqualTo(CreditsVerdict.UNSURE);
        assertThat(unknown.markerMs()).isNull();
    }
}
