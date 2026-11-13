package gregochr.cinefilm.opensubtitles;

import java.time.Instant;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Persistence for {@link OpenSubtitlesBudgetDay}; each change is one atomic statement. */
public interface OpenSubtitlesBudgetRepository extends JpaRepository<OpenSubtitlesBudgetDay, LocalDate> {

    /** Spends one download if the day is still under the limit; returns 1 when it did, 0 when spent or no row yet. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OpenSubtitlesBudgetDay b set b.used = b.used + 1 where b.budgetDay = :day and b.used < :limit")
    int spendOne(@Param("day") LocalDate day, @Param("limit") int limit);

    /** Gives one spent download back (the request did not reach OpenSubtitles' counter); returns the rows changed. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OpenSubtitlesBudgetDay b set b.used = b.used - 1 where b.budgetDay = :day and b.used > 0")
    int refundOne(@Param("day") LocalDate day);

    /**
     * Marks the day spent (raises {@code used} to the limit) and blocks downloads until a time; a later block is
     * never replaced by an earlier one. Returns the rows changed.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OpenSubtitlesBudgetDay b set b.used = case when b.used < :limit then :limit else b.used end, "
        + "b.blockedUntil = case when b.blockedUntil is null or b.blockedUntil < :until then :until "
        + "else b.blockedUntil end where b.budgetDay = :day")
    int spendAll(@Param("day") LocalDate day, @Param("limit") int limit, @Param("until") Instant until);

    /**
     * Raises {@code used} to a count OpenSubtitles reports (the account may be shared with other tools); never lowers
     * it. Returns the rows changed.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OpenSubtitlesBudgetDay b set b.used = case when b.used < :used then :used else b.used end "
        + "where b.budgetDay = :day")
    int raiseUsed(@Param("day") LocalDate day, @Param("used") int used);

    /** The latest instant any day is blocked until, or null when none is blocked. */
    @Query("select max(b.blockedUntil) from OpenSubtitlesBudgetDay b")
    Instant findLatestBlockedUntil();
}
