package gregochr.cinefilm.opensubtitles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Two callers creating the same day's row at once: the loser's failed insert is harmless and it still spends. */
class OpenSubtitlesBudgetRaceTest {

    private static final LocalDate DAY = LocalDate.of(2024, 3, 31);

    private final OpenSubtitlesBudgetRepository days = mock(OpenSubtitlesBudgetRepository.class);
    private final OpenSubtitlesBudget budget = new OpenSubtitlesBudget(days);

    @Test
    void losingTheRaceToCreateTheDaysRowStillSpendsFromIt() {
        when(days.spendOne(DAY, 90)).thenReturn(0, 1);
        when(days.existsById(DAY)).thenReturn(false);
        when(days.saveAndFlush(org.mockito.ArgumentMatchers.any(OpenSubtitlesBudgetDay.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThat(budget.take(DAY, 90)).isTrue();
    }
}
