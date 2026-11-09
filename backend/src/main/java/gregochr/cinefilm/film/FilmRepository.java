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

    /** True when some film holds the path as its poster or backdrop. */
    boolean existsByPosterPathOrBackdropPath(String posterPath, String backdropPath);

    /** Every poster path that points into the artwork store. */
    @org.springframework.data.jpa.repository.Query(
        "select f.posterPath from Film f where f.posterPath like '/api/art/%'")
    List<String> findStoredPosterPaths();

    /** Every backdrop path that points into the artwork store. */
    @org.springframework.data.jpa.repository.Query(
        "select f.backdropPath from Film f where f.backdropPath like '/api/art/%'")
    List<String> findStoredBackdropPaths();

    /** Every film matched to a TMDB id, oldest first. */
    List<Film> findByTmdbIdOrderByIdAsc(Long tmdbId);

    /** Films in the given match state. */
    List<Film> findByMatchStatusOrderBySortTitle(MatchStatus status);
}
