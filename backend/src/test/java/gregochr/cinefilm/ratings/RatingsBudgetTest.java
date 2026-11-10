package gregochr.cinefilm.ratings;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.ApiTestBase;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The persisted daily budget against the real schema (Flyway on H2): rows, the limit boundary and the day roll. */
class RatingsBudgetTest extends ApiTestBase {

    private static final LocalDate DAY = LocalDate.of(2024, 3, 31);

    @Autowired
    private RatingsBudget budget;

    @Autowired
    private RatingsBudgetRepository days;

    @Test
    void callsAreGrantedUpToTheLimitAndNoFurtherAndTheCountIsStored() {
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
    void exhaustingSpendsTheRestOfTheDayAndStaysSpent() {
        budget.take(DAY, 900);

        budget.exhaust(DAY, 900);

        assertThat(days.findById(DAY).orElseThrow().getUsed()).isEqualTo(900);
        assertThat(budget.take(DAY, 900)).isFalse();
    }

    @Test
    void exhaustingADayWithNoRowYetCreatesItSpent() {
        budget.exhaust(DAY, 900);

        assertThat(days.findById(DAY).orElseThrow().getUsed()).isEqualTo(900);
        assertThat(budget.take(DAY, 900)).isFalse();
    }
}
