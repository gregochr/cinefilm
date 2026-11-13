package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** How the day's OpenSubtitles download budget is shared between missing, replacement and comparison downloads. */
class DownloadSharesTest {

    @Test
    void aFreeAccountsTwentyGivesMissingAllReplacementsFourteenAndComparisonsSix() {
        assertThat(DownloadShares.forMissing(20)).isEqualTo(20);
        assertThat(DownloadShares.forReplacements(20)).isEqualTo(14);
        assertThat(DownloadShares.forChecks(20)).isEqualTo(6);
    }

    @Test
    void aVipAccountsThousandGivesAThousandSevenHundredAndThreeHundred() {
        assertThat(DownloadShares.forMissing(1000)).isEqualTo(1000);
        assertThat(DownloadShares.forReplacements(1000)).isEqualTo(700);
        assertThat(DownloadShares.forChecks(1000)).isEqualTo(300);
    }

    @Test
    void sharesAreExactIntegersRoundedDown() {
        assertThat(DownloadShares.forReplacements(11)).isEqualTo(7);
        assertThat(DownloadShares.forChecks(11)).isEqualTo(3);
        assertThat(DownloadShares.forReplacements(1)).isZero();
        assertThat(DownloadShares.forChecks(1)).isZero();
        assertThat(DownloadShares.forChecks(100)).isEqualTo(30);
    }

    @Test
    void aLowerPriorityJobNeverHasMoreThanAHigherOne() {
        for (int limit = 0; limit <= 200; limit++) {
            assertThat(DownloadShares.forChecks(limit)).isLessThanOrEqualTo(DownloadShares.forReplacements(limit));
            assertThat(DownloadShares.forReplacements(limit)).isLessThanOrEqualTo(DownloadShares.forMissing(limit));
        }
    }

    @Test
    void aZeroOrNegativeLimitGivesNothing() {
        assertThat(DownloadShares.forReplacements(0)).isZero();
        assertThat(DownloadShares.forChecks(0)).isZero();
        assertThat(DownloadShares.forReplacements(-5)).isZero();
        assertThat(DownloadShares.forChecks(-5)).isZero();
    }
}
