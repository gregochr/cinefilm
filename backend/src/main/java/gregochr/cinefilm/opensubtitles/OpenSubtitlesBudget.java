package gregochr.cinefilm.opensubtitles;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * The daily OpenSubtitles download budget, kept in the database so a restart does not grant a fresh allowance. Every
 * change is one conditional statement, so two callers racing cannot overspend; the day's row is created on first use
 * (a lost race to create it is harmless). When OpenSubtitles says its own limit is reached the day is spent and
 * nothing is downloaded before the time it gave (or, when it gave none, the start of the next UTC day).
 */
@Component
public class OpenSubtitlesBudget {

    private final OpenSubtitlesBudgetRepository days;

    /** Creates the budget. */
    public OpenSubtitlesBudget(OpenSubtitlesBudgetRepository days) {
        this.days = days;
    }

    /**
     * Spends one download from the day's budget.
     *
     * @param day the UTC day
     * @param limit downloads allowed that day
     * @return true when a download was available (and is now spent); false when the day's budget is spent
     */
    public boolean take(LocalDate day, int limit) {
        if (days.spendOne(day, limit) == 1) {
            return true;
        }
        ensure(day);
        return days.spendOne(day, limit) == 1;
    }

    /** Gives back a download that was taken but did not reach OpenSubtitles (an outage). */
    public void refund(LocalDate day) {
        days.refundOne(day);
    }

    /**
     * Spends what is left of the day's budget: OpenSubtitles said its own limit is reached.
     *
     * @param day the UTC day
     * @param limit the day's limit
     * @param resetTimeUtc when OpenSubtitles said the allowance comes back, or null (then the next UTC midnight)
     */
    public void exhaust(LocalDate day, int limit, Instant resetTimeUtc) {
        ensure(day);
        Instant until = resetTimeUtc != null ? resetTimeUtc
            : day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        days.spendAll(day, limit, until);
    }

    /**
     * When downloads are blocked until, if that is still ahead.
     *
     * @param now the current instant
     * @return the instant OpenSubtitles' allowance comes back, or null when downloads are not blocked
     */
    public Instant blockedUntil(Instant now) {
        Instant until = days.findLatestBlockedUntil();
        return until != null && until.isAfter(now) ? until : null;
    }

    /** Makes sure the day has a row. */
    private void ensure(LocalDate day) {
        if (!days.existsById(day)) {
            try {
                days.saveAndFlush(new OpenSubtitlesBudgetDay(day));
            } catch (DataIntegrityViolationException e) {
                // another caller created it first
            }
        }
    }
}
