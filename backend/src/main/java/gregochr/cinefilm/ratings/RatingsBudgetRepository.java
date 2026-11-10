package gregochr.cinefilm.ratings;

import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Persistence for {@link RatingsBudgetDay}; each change is one atomic statement. */
public interface RatingsBudgetRepository extends JpaRepository<RatingsBudgetDay, LocalDate> {

    /** Spends one request if the day is still under the limit; returns 1 when it did, 0 when spent or no row yet. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RatingsBudgetDay b set b.used = b.used + 1 where b.budgetDay = :day and b.used < :limit")
    int spendOne(@Param("day") LocalDate day, @Param("limit") int limit);

    /** Marks the day spent (raises {@code used} to the limit); returns the rows changed. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RatingsBudgetDay b set b.used = :limit where b.budgetDay = :day and b.used < :limit")
    int spendAll(@Param("day") LocalDate day, @Param("limit") int limit);
}
