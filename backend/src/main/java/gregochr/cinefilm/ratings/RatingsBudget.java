package gregochr.cinefilm.ratings;

import java.time.LocalDate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * The daily OMDb request budget, kept in the database so a restart does not grant a fresh allowance. Every change is
 * one conditional statement, so two callers racing cannot overspend; the day's row is created on first use (a lost
 * race to create it is harmless).
 */
@Component
public class RatingsBudget {

    private final RatingsBudgetRepository days;

    /** Creates the budget. */
    public RatingsBudget(RatingsBudgetRepository days) {
        this.days = days;
    }

    /**
     * Spends one request from the day's budget.
     *
     * @param day the UTC day
     * @param limit requests allowed that day
     * @return true when a request was available (and is now spent); false when the day's budget is spent
     */
    public boolean take(LocalDate day, int limit) {
        if (days.spendOne(day, limit) == 1) {
            return true;
        }
        ensure(day);
        return days.spendOne(day, limit) == 1;
    }

    /** Spends what is left of the day's budget (OMDb said its own limit is reached). */
    public void exhaust(LocalDate day, int limit) {
        ensure(day);
        days.spendAll(day, limit);
    }

    /** Makes sure the day has a row. */
    private void ensure(LocalDate day) {
        if (!days.existsById(day)) {
            try {
                days.saveAndFlush(new RatingsBudgetDay(day));
            } catch (DataIntegrityViolationException e) {
                // another caller created it first
            }
        }
    }
}
