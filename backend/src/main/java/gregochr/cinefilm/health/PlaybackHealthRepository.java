package gregochr.cinefilm.health;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Persistence for {@link PlaybackHealth}. */
public interface PlaybackHealthRepository extends JpaRepository<PlaybackHealth, Long> {

    /** The record of one viewing, if there is one. */
    Optional<PlaybackHealth> findByPlaybackId(String playbackId);

    /** The 20 records with the newest {@code updatedAt}, newest first (the higher id first on a tie). */
    List<PlaybackHealth> findTop20ByOrderByUpdatedAtDescIdDesc();

    /**
     * Replaces a viewing's report in one statement, only when the stored {@code seq} is lower than the new one, so an
     * older report that arrives late (two sends can overlap) never overwrites a newer one. The film, the start and
     * the edition are never changed. Returns the rows changed: 0 when the stored report is as new or newer. The
     * persistence context is flushed first and cleared afterwards.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PlaybackHealth h set h.summary = :summary, h.seq = :seq, h.updatedAt = :updatedAt "
        + "where h.playbackId = :playbackId and h.seq < :seq")
    int replaceIfNewer(String playbackId, int seq, String summary, Instant updatedAt);

    /** The ids of the records that were updated longest ago, oldest first (the lower id first on a tie). */
    @Query("select h.id from PlaybackHealth h order by h.updatedAt asc, h.id asc")
    List<Long> oldestIds(Pageable page);
}
