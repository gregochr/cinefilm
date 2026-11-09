package gregochr.cinefilm.film;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link Film}. */
public interface FilmRepository extends JpaRepository<Film, Long> {

    /** The films belonging to a collection. */
    List<Film> findByCollectionId(Long collectionId);

    /** The film matched to a TMDB id, if any (the first when several exist). */
    Optional<Film> findFirstByTmdbIdOrderByIdAsc(Long tmdbId);

    /** Every film matched to a TMDB id, oldest first. */
    List<Film> findByTmdbIdOrderByIdAsc(Long tmdbId);

    /** Films in the given match state. */
    List<Film> findByMatchStatusOrderBySortTitle(MatchStatus status);
}
