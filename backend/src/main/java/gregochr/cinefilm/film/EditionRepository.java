package gregochr.cinefilm.film;

import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Persistence for {@link Edition}. */
public interface EditionRepository extends JpaRepository<Edition, Long> {

    /**
     * Moves every edition of one film to another, keeping each edition's id (and so its watch history). The
     * persistence context is cleared afterwards: reload entities before using them.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Edition e set e.film = :target where e.film = :source")
    int moveAll(Film source, Film target);

    /** Moves one edition to another film, keeping its id. The persistence context is cleared: reload afterwards. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Edition e set e.film = :target where e.id = :editionId")
    int moveOne(Long editionId, Film target);

    /**
     * Deletes editions by id in one statement (no cascade: the caller has already moved their files out and removed
     * their playback state). The persistence context is flushed first and cleared afterwards.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Edition e where e.id in :ids")
    int deleteByIds(Collection<Long> ids);

    /** How many editions a film has. */
    long countByFilmId(Long filmId);
}
