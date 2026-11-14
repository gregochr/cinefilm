package gregochr.cinefilm.health;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Persistence for {@link PlaybackHealth}. */
public interface PlaybackHealthRepository
    extends JpaRepository<PlaybackHealth, Long>, JpaSpecificationExecutor<PlaybackHealth> {

    /** The record of one viewing, if there is one. */
    Optional<PlaybackHealth> findByPlaybackId(String playbackId);

    /** The 20 records with the newest {@code updatedAt}, newest first (the higher id first on a tie). */
    List<PlaybackHealth> findTop20ByOrderByUpdatedAtDescIdDesc();

    /**
     * Replaces a viewing's report in one statement, only when the stored {@code seq} is lower than the new one, so an
     * older report that arrives late (two sends can overlap) never overwrites a newer one. The film, the start and
     * the edition are never changed. Returns the rows changed: 0 when the stored report is as new or newer. The
     * persistence context is flushed first and cleared afterwards. The figure columns are replaced with the report
     * (see {@link HealthFigures}), so they never go stale.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PlaybackHealth h set h.summary = :summary, h.seq = :seq, h.updatedAt = :updatedAt, "
        + "h.watchedMs = :watchedMs, h.stallCount = :stallCount, h.stallMs = :stallMs, h.startupMs = :startupMs, "
        + "h.hasProblem = :hasProblem, h.environment = :environment "
        + "where h.playbackId = :playbackId and h.seq < :seq")
    int replaceIfNewerWith(String playbackId, int seq, String summary, Instant updatedAt, long watchedMs,
                           long stallCount, long stallMs, Long startupMs, boolean hasProblem, String environment);

    /** {@link #replaceIfNewerWith} taking the figures as {@link HealthFigures}. */
    default int replaceIfNewer(String playbackId, int seq, String summary, Instant updatedAt,
                               HealthFigures figures) {
        return replaceIfNewerWith(playbackId, seq, summary, updatedAt, figures.watchedMs(), figures.stallCount(),
            figures.stallMs(), figures.startupMs(), figures.hasProblem(), figures.environment());
    }

    /** The ids of the records that were updated longest ago, oldest first (the lower id first on a tie). */
    @Query("select h.id from PlaybackHealth h order by h.updatedAt asc, h.id asc")
    List<Long> oldestIds(Pageable page);
}
