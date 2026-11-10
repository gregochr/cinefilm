package gregochr.cinefilm.playback;

import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Persistence for {@link PlaybackState}. */
public interface PlaybackStateRepository extends JpaRepository<PlaybackState, Long> {

    /**
     * Deletes the state of the given editions in one statement; it must run before the editions themselves are
     * deleted (the foreign key). The persistence context is flushed first and cleared afterwards.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PlaybackState s where s.editionId in :ids")
    int deleteByEditionIds(Collection<Long> ids);
}
