package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** How the day's OpenSubtitles download budget is shared between missing, replacement and comparison downloads. */
class DownloadSharesTest {

    @Test
    void aLimitOf90GivesMissingAllReplacementsSixtyThreeAndComparisonsFortyFive() {
        assertThat(DownloadShares.forMissing(90)).isEqualTo(90);
        assertThat(DownloadShares.forReplacements(90)).isEqualTo(63);
        assertThat(DownloadShares.forChecks(90)).isEqualTo(45);
    }

    @Test
    void sharesAreExactIntegersRoundedDown() {
        assertThat(DownloadShares.forReplacements(11)).isEqualTo(7);
        assertThat(DownloadShares.forChecks(11)).isEqualTo(5);
        assertThat(DownloadShares.forReplacements(1)).isZero();
        assertThat(DownloadShares.forChecks(1)).isZero();
        assertThat(DownloadShares.forChecks(100)).isEqualTo(50);
    }

    @Test
    void aLowerPriorityJobNeverHasMoreThanAHigherOne() {
        for (int limit = 0; limit <= 200; limit++) {
            assertThat(DownloadShares.forChecks(limit)).isLessThanOrEqualTo(DownloadShares.forReplacements(limit));
            assertThat(DownloadShares.forReplacements(limit)).isLessThanOrEqualTo(DownloadShares.forMissing(limit));
        }
    }

    @Test
    void aNegativeLimitGivesNothing() {
        assertThat(DownloadShares.forReplacements(-5)).isZero();
        assertThat(DownloadShares.forChecks(-5)).isZero();
    }
}
