package gregochr.cinefilm.opensubtitles;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.ApiTestBase;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The persisted daily download budget against the real schema (Flyway on H2): rows, the limit boundary, the UTC day
 * roll, exhaustion by OpenSubtitles' own limit, and the reset time that blocks downloads.
 */
class OpenSubtitlesBudgetTest extends ApiTestBase {

    private static final LocalDate DAY = LocalDate.of(2024, 3, 31);
    private static final Instant MIDNIGHT = Instant.parse("2024-04-01T00:00:00Z");

    @Autowired
    private OpenSubtitlesBudget budget;

    @Autowired
    private OpenSubtitlesBudgetRepository days;

    @Test
    void downloadsAreGrantedUpToTheLimitAndNoFurtherAndTheCountIsStored() {
        assertThat(budget.take(DAY, 2)).isTrue();
        assertThat(budget.take(DAY, 2)).isTrue();
        assertThat(budget.take(DAY, 2)).isFalse();

        assertThat(days.findById(DAY).orElseThrow().getUsed()).isEqualTo(2);
    }

    @Test
    void aLimitOfZeroGrantsNothing() {
        assertThat(budget.take(DAY, 0)).isFalse();
    }

    @Test
    void aNewUtcDayStartsAtZeroWhileTheOldDaysRowStays() {
        budget.take(DAY, 1);

        assertThat(budget.take(DAY, 1)).isFalse();
        assertThat(budget.take(DAY.plusDays(1), 1)).isTrue();
        assertThat(days.findById(DAY).orElseThrow().getUsed()).isEqualTo(1);
    }

    @Test
    void aRefundGivesOneDownloadBackAndNeverGoesBelowZero() {
        budget.take(DAY, 2);
        budget.take(DAY, 2);

        budget.refund(DAY);

        assertThat(days.findById(DAY).orElseThrow().getUsed()).isEqualTo(1);
        assertThat(budget.take(DAY, 2)).isTrue();
        budget.refund(DAY);
        budget.refund(DAY);
        budget.refund(DAY);
        assertThat(days.findById(DAY).orElseThrow().getUsed()).isZero();
    }

    @Test
    void aRefundOnADayWithNoRowChangesNothing() {
        budget.refund(DAY);

        assertThat(days.findById(DAY)).isEmpty();
    }

    @Test
    void exhaustingSpendsTheRestOfTheDayAndBlocksUntilTheResetTimeOpenSubtitlesGave() {
        budget.take(DAY, 90);
        Instant reset = Instant.parse("2024-04-01T00:00:00Z");

        budget.exhaust(DAY, 90, reset);

        OpenSubtitlesBudgetDay row = days.findById(DAY).orElseThrow();
        assertThat(row.getUsed()).isEqualTo(90);
        assertThat(row.getBlockedUntil()).isEqualTo(reset);
        assertThat(budget.take(DAY, 90)).isFalse();
    }

    @Test
    void exhaustingWithNoResetTimeBlocksUntilTheNextUtcMidnight() {
        budget.exhaust(DAY, 90, null);

        assertThat(days.findById(DAY).orElseThrow().getBlockedUntil()).isEqualTo(MIDNIGHT);
    }

    @Test
    void exhaustingADayWithNoRowYetCreatesItSpent() {
        budget.exhaust(DAY, 90, MIDNIGHT);

        assertThat(days.findById(DAY).orElseThrow().getUsed()).isEqualTo(90);
        assertThat(budget.take(DAY, 90)).isFalse();
    }

    @Test
    void exhaustingNeverLowersADayThatIsAlreadyOverTheLimitOrBringsAnEarlierBlockForward() {
        budget.take(DAY, 90);
        budget.exhaust(DAY, 90, Instant.parse("2024-04-01T06:00:00Z"));
        budget.exhaust(DAY, 50, Instant.parse("2024-04-01T01:00:00Z"));

        OpenSubtitlesBudgetDay row = days.findById(DAY).orElseThrow();
        assertThat(row.getUsed()).isEqualTo(90);
        assertThat(row.getBlockedUntil()).isEqualTo(Instant.parse("2024-04-01T06:00:00Z"));
    }

    @Test
    void aLaterBlockReplacesAnEarlierOne() {
        budget.exhaust(DAY, 90, Instant.parse("2024-04-01T01:00:00Z"));
        budget.exhaust(DAY, 90, Instant.parse("2024-04-01T06:00:00Z"));

        assertThat(days.findById(DAY).orElseThrow().getBlockedUntil())
            .isEqualTo(Instant.parse("2024-04-01T06:00:00Z"));
    }

    @Test
    void downloadsAreBlockedBeforeTheResetTimeNotAtItAndNotAfter() {
        budget.exhaust(DAY, 90, MIDNIGHT);

        assertThat(budget.blockedUntil(MIDNIGHT.minusMillis(1))).isEqualTo(MIDNIGHT);
        assertThat(budget.blockedUntil(MIDNIGHT)).isNull();
        assertThat(budget.blockedUntil(MIDNIGHT.plusMillis(1))).isNull();
    }

    @Test
    void raisingTheCountToWhatOpenSubtitlesReportsNeverLowersIt() {
        budget.take(DAY, 20);
        budget.take(DAY, 20);
        budget.take(DAY, 20);

        budget.raiseUsed(DAY, 9);
        assertThat(days.findById(DAY).orElseThrow().getUsed()).isEqualTo(9);

        budget.raiseUsed(DAY, 4);
        assertThat(days.findById(DAY).orElseThrow().getUsed()).isEqualTo(9);

        budget.raiseUsed(DAY, 9);
        assertThat(days.findById(DAY).orElseThrow().getUsed()).isEqualTo(9);
        assertThat(budget.take(DAY, 10)).isTrue();
        assertThat(budget.take(DAY, 10)).isFalse();
    }

    @Test
    void raisingTheCountOnADayWithNoRowCreatesItAndLeavesOtherDaysAlone() {
        budget.take(DAY.plusDays(1), 20);

        budget.raiseUsed(DAY, 6);

        assertThat(days.findById(DAY).orElseThrow().getUsed()).isEqualTo(6);
        assertThat(days.findById(DAY.plusDays(1)).orElseThrow().getUsed()).isEqualTo(1);
    }

    @Test
    void withNothingExhaustedNothingIsBlocked() {
        budget.take(DAY, 90);

        assertThat(budget.blockedUntil(MIDNIGHT.minusSeconds(3600))).isNull();
    }

    @Test
    void theResetTimeSurvivesTheNextDaysRowBeingCreated() {
        budget.exhaust(DAY, 90, Instant.parse("2024-04-01T06:00:00Z"));
        budget.take(DAY.plusDays(1), 90);

        assertThat(budget.blockedUntil(Instant.parse("2024-04-01T05:00:00Z")))
            .isEqualTo(Instant.parse("2024-04-01T06:00:00Z"));
    }
}
