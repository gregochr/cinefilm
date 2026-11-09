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

    /** Ids of films whose ratings were never fetched or were fetched before the cutoff: never first, then oldest. */
    @org.springframework.data.jpa.repository.Query(
        "select f.id from Film f where f.ratingsFetchedAt is null or f.ratingsFetchedAt < :cutoff "
            + "order by f.ratingsFetchedAt asc nulls first, f.id asc")
    List<Long> findIdsWithRatingsDueBefore(
        @org.springframework.data.repository.query.Param("cutoff") java.time.Instant cutoff);

    /** Ids of every film: ratings never fetched first, then the oldest fetch. */
    @org.springframework.data.jpa.repository.Query(
        "select f.id from Film f order by f.ratingsFetchedAt asc nulls first, f.id asc")
    List<Long> findIdsByRatingsOldestFirst();

    /** When ratings were last fetched for any film, or null when never. */
    @org.springframework.data.jpa.repository.Query("select max(f.ratingsFetchedAt) from Film f")
    java.time.Instant findLatestRatingsFetchedAt();
}
