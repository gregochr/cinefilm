package gregochr.cinefilm.film;

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
}
