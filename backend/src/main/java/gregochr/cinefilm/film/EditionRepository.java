package gregochr.cinefilm.film;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Persistence for {@link Edition}. */
public interface EditionRepository extends JpaRepository<Edition, Long> {

    /**
     * The editions of the given films with their files and their film loaded in one statement (a fixed number of
     * queries however many films), for code that reads every edition's parts. Only the files are collection-fetched,
     * so there is no cartesian product of two lists; {@code distinct} drops the repeats the join makes.
     */
    @Query("select distinct e from Edition e join fetch e.mediaFiles join fetch e.film where e.film.id in :filmIds")
    List<Edition> findWithFilesByFilmIdIn(Collection<Long> filmIds);

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
