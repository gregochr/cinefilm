package gregochr.cinefilm.playback;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Persistence for {@link PlaybackState}. */
public interface PlaybackStateRepository extends JpaRepository<PlaybackState, Long> {

    /**
     * The editions in progress that have not been removed from Continue watching: a position past 0 and not hidden.
     */
    @Query("select s.editionId from PlaybackState s where s.positionMs > 0 and s.hiddenFromContinueWatching = false")
    List<Long> findInProgressEditionIds();

    /**
     * When playback progress was last reported for any edition, or null when none ever was. A progress report sets
     * {@code lastPlayedAt} every time, so this is the last time anything was playing.
     */
    @Query("select max(s.lastPlayedAt) from PlaybackState s")
    Instant findLatestLastPlayedAt();

    /**
     * Deletes the state of the given editions in one statement; it must run before the editions themselves are
     * deleted (the foreign key). The persistence context is flushed first and cleared afterwards.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PlaybackState s where s.editionId in :ids")
    int deleteByEditionIds(Collection<Long> ids);

    /**
     * How many of the given editions have a state in progress (a position past 0, not removed from Continue watching)
     * whose saved position is at or past the edition's credits marker.
     */
    @Query("select count(s) from PlaybackState s join Edition e on e.id = s.editionId "
        + "where e.id in :editionIds and e.creditsMarkerMs is not null and s.positionMs > 0 "
        + "and s.hiddenFromContinueWatching = false and s.positionMs >= e.creditsMarkerMs")
    long countInProgressAtOrPastMarker(Collection<Long> editionIds);
}
