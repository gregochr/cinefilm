package gregochr.cinefilm.film;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link Film}. */
public interface FilmRepository extends JpaRepository<Film, Long> {

    /** The films belonging to a collection. */
    List<Film> findByCollectionId(Long collectionId);
}
