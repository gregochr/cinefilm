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

    /** The film, row-locked until the transaction ends, so a check-then-write on it cannot interleave with another. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select f from Film f where f.id = :id")
    Optional<Film> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    /** Every film matched to a TMDB id, oldest first. */
    List<Film> findByTmdbIdOrderByIdAsc(Long tmdbId);

    /** Films with more than one edition (the ones that have a default to choose). */
    @org.springframework.data.jpa.repository.Query("select f from Film f where size(f.editions) > 1 order by f.id")
    List<Film> findWithSeveralEditions();

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

    /**
     * A film and the TMDB id it was matched to.
     *
     * @param id the film
     * @param tmdbId its TMDB id
     */
    record TmdbRef(Long id, Long tmdbId) {
    }

    /** Films matched to TMDB whose languages have never been asked for, oldest first. */
    @org.springframework.data.jpa.repository.Query(
        "select new gregochr.cinefilm.film.FilmRepository$TmdbRef(f.id, f.tmdbId) from Film f "
            + "where f.tmdbId is not null and f.languagesFetchedAt is null order by f.id")
    List<TmdbRef> findWithoutLanguages();

    /**
     * Stores TMDB's languages and stamps the film, in one statement that only matches while the film still has the
     * TMDB id they were fetched for: a film rematched meanwhile is left alone.
     *
     * @return the rows changed: 1, or 0 when the film has gone or now has another TMDB id
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(
        "update Film f set f.originalLanguage = :original, f.spokenLanguages = :spoken, "
            + "f.languagesFetchedAt = :at where f.id = :id and f.tmdbId = :tmdbId")
    int saveLanguages(@org.springframework.data.repository.query.Param("id") Long id,
                      @org.springframework.data.repository.query.Param("tmdbId") Long tmdbId,
                      @org.springframework.data.repository.query.Param("original") String original,
                      @org.springframework.data.repository.query.Param("spoken") String spoken,
                      @org.springframework.data.repository.query.Param("at") java.time.Instant at);

    /**
     * What the forced-subtitle need is worked out from, for one film.
     *
     * @param id the film
     * @param title its title
     * @param year its year, or null
     * @param tmdbId its TMDB id, or null
     * @param originalLanguage TMDB's original language, or null
     * @param spokenLanguages TMDB's spoken languages as stored, or null
     * @param languagesFetchedAt when TMDB was asked, or null
     * @param owner the owner's choice, or null
     */
    record NeedRow(Long id, String title, Integer year, Long tmdbId, String originalLanguage, String spokenLanguages,
                   java.time.Instant languagesFetchedAt, ForcedNeedChoice owner) {
    }

    /** Every film with the columns the need is worked out from, by sort title. */
    @org.springframework.data.jpa.repository.Query(
        "select new gregochr.cinefilm.film.FilmRepository$NeedRow(f.id, f.title, f.year, f.tmdbId, "
            + "f.originalLanguage, f.spokenLanguages, f.languagesFetchedAt, f.forcedNeedOwner) from Film f "
            + "order by f.sortTitle, f.id")
    List<NeedRow> findNeedRows();

    /** When ratings were last fetched for any film, or null when never. */
    @org.springframework.data.jpa.repository.Query("select max(f.ratingsFetchedAt) from Film f")
    java.time.Instant findLatestRatingsFetchedAt();
}
