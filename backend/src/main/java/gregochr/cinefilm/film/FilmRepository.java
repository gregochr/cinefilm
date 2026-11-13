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
     * Writes a film's languages and stamp straight to the row, whatever the entity thinks it loaded. A rematch uses it
     * in its own transaction after the TMDB id change has been flushed: the entity (with {@code @DynamicUpdate}) skips
     * a column it loaded as null and sets to null, so a backfill that committed the old film's languages meanwhile
     * would survive; this statement overwrites it, and a backfill still waiting on the row finds its TMDB id gone.
     * The persistence context is kept, so the entity must already hold these values.
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
        "update Film f set f.originalLanguage = :original, f.spokenLanguages = :spoken, "
            + "f.languagesFetchedAt = :at where f.id = :id")
    int writeLanguages(@org.springframework.data.repository.query.Param("id") Long id,
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
     * @param imdbId its IMDb id, or null
     * @param osForcedCount how many foreign-parts-only files OpenSubtitles had, or null when never searched
     * @param osSearchedAt when OpenSubtitles was last searched, or null
     * @param osSearchedImdbId the IMDb id that search was for, or null
     */
    record NeedRow(Long id, String title, Integer year, Long tmdbId, String originalLanguage, String spokenLanguages,
                   java.time.Instant languagesFetchedAt, ForcedNeedChoice owner, String imdbId,
                   Integer osForcedCount, java.time.Instant osSearchedAt, String osSearchedImdbId) {
    }

    /**
     * A film and its IMDb id.
     *
     * @param id the film
     * @param imdbId its IMDb id
     */
    record ImdbRef(Long id, String imdbId) {
    }

    /**
     * Films with an IMDb id whose OpenSubtitles search is due: never searched, searched under another IMDb id, or
     * searched with no result before the cutoff, or holding a best result stored before the file count was kept (no
     * {@code fileCount} in its JSON: it may be one disc of several, so it is searched again, those first), or
     * searched before the candidates were kept ({@code os_candidates} null: those with a best result first).
     * Never-searched next, then the oldest search.
     */
    @org.springframework.data.jpa.repository.Query(
        "select new gregochr.cinefilm.film.FilmRepository$ImdbRef(f.id, f.imdbId) from Film f "
            + "where f.imdbId is not null and (f.osSearchedAt is null or f.osSearchedImdbId is null "
            + "or f.osSearchedImdbId <> f.imdbId or (f.osForcedCount = 0 and f.osSearchedAt < :cutoff) "
            + "or f.osCandidates is null "
            + "or (f.osBest is not null and f.osBest not like '%\"fileCount\"%')) "
            + "order by case when f.osBest is not null and (f.osCandidates is null "
            + "or f.osBest not like '%\"fileCount\"%') then 0 else 1 end, "
            + "f.osSearchedAt asc nulls first, f.id asc")
    List<ImdbRef> findOpenSubtitlesDue(
        @org.springframework.data.repository.query.Param("cutoff") java.time.Instant cutoff);

    /**
     * Stores what OpenSubtitles said and stamps the film, in one statement that only matches while the film still
     * has the IMDb id it was searched for: a film whose IMDb id changed meanwhile is left alone.
     *
     * @return the rows changed: 1, or 0 when the film has gone or now has another IMDb id
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(
        "update Film f set f.osForcedCount = :count, f.osBest = :best, f.osCandidates = :candidates, "
            + "f.osSearchedImdbId = :imdbId, f.osSearchedAt = :at where f.id = :id and f.imdbId = :imdbId")
    int saveOpenSubtitles(@org.springframework.data.repository.query.Param("id") Long id,
                          @org.springframework.data.repository.query.Param("imdbId") String imdbId,
                          @org.springframework.data.repository.query.Param("count") int count,
                          @org.springframework.data.repository.query.Param("best") String best,
                          @org.springframework.data.repository.query.Param("candidates") String candidates,
                          @org.springframework.data.repository.query.Param("at") java.time.Instant at);

    /**
     * Forgets what OpenSubtitles said about a film, straight on the row. A change of IMDb id uses it in its own
     * transaction after the id change has been flushed (the flush is automatic): the entity (with
     * {@code @DynamicUpdate}) skips a column it loaded as null and sets to null, so a search that committed the old
     * film's result meanwhile would survive; this statement removes it, and a search still waiting on the row finds
     * its IMDb id gone. The persistence context is kept, so the entity must already hold these values.
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
        "update Film f set f.osForcedCount = null, f.osBest = null, f.osCandidates = null, "
            + "f.osSearchedImdbId = null, f.osSearchedAt = null where f.id = :id")
    int clearOpenSubtitles(@org.springframework.data.repository.query.Param("id") Long id);

    /**
     * Removes the subtitles downloaded from OpenSubtitles for a film's editions (a change of IMDb id means they were
     * for another film). The scan and everything else leave downloads alone; this and the owner's removal are the only
     * deletions. The timing and comparison rows of a download go with it (foreign keys cascade). It deliberately
     * ignores the subtitle kind: forced and full downloads were both fetched for the old IMDb id, so both go.
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
        "delete from SubtitleSidecar s "
            + "where s.origin = gregochr.cinefilm.subtitle.SubtitleSidecar.Origin.OPENSUBTITLES "
            + "and s.mediaFileId in (select m.id from MediaFile m join m.edition e where e.film.id = :id)")
    int deleteOpenSubtitlesDownloads(@org.springframework.data.repository.query.Param("id") Long id);

    /**
     * A film's best OpenSubtitles result.
     *
     * @param id the film
     * @param imdbId its current IMDb id
     * @param osBest the top-ranked result as JSON ({@code OpenSubtitlesResult.toJson})
     * @param osCandidates the candidates as a JSON array ({@code OpenSubtitlesResult.listToJson}); null when stored
     *     before they were kept
     */
    record OsBestRef(Long id, String imdbId, String osBest, String osCandidates) {
    }

    /**
     * Films that have a best OpenSubtitles result from a search of their current IMDb id (at least one result). A
     * count or result from a search under another IMDb id does not count.
     */
    @org.springframework.data.jpa.repository.Query(
        "select new gregochr.cinefilm.film.FilmRepository$OsBestRef(f.id, f.imdbId, f.osBest, f.osCandidates) "
            + "from Film f where f.imdbId is not null and f.osSearchedImdbId = f.imdbId and f.osForcedCount > 0 "
            + "and f.osBest is not null order by f.id")
    List<OsBestRef> findOpenSubtitlesBest();

    /** When OpenSubtitles was last searched for any film, or null when never. */
    @org.springframework.data.jpa.repository.Query("select max(f.osSearchedAt) from Film f")
    java.time.Instant findLatestOpenSubtitlesSearchedAt();

    /** Every film with the columns the need is worked out from, by sort title. */
    @org.springframework.data.jpa.repository.Query(
        "select new gregochr.cinefilm.film.FilmRepository$NeedRow(f.id, f.title, f.year, f.tmdbId, "
            + "f.originalLanguage, f.spokenLanguages, f.languagesFetchedAt, f.forcedNeedOwner, "
            + "f.imdbId, f.osForcedCount, f.osSearchedAt, f.osSearchedImdbId) from Film f "
            + "order by f.sortTitle, f.id")
    List<NeedRow> findNeedRows();

    /**
     * What the full-subtitles need is worked out from, per film.
     *
     * @param id the film
     * @param title its title
     * @param year its year, or null
     * @param tmdbId its TMDB id, or null
     * @param originalLanguage TMDB's original language, or null
     * @param spokenLanguages TMDB's spoken languages as stored, or null
     * @param languagesFetchedAt when TMDB was last asked, or null
     * @param owner the owner's choice, or null
     */
    record FullNeedRow(Long id, String title, Integer year, Long tmdbId, String originalLanguage,
                       String spokenLanguages, java.time.Instant languagesFetchedAt, FullNeedChoice owner) {
    }

    /** Every film with the columns the full-subtitles need is worked out from, by sort title. */
    @org.springframework.data.jpa.repository.Query(
        "select new gregochr.cinefilm.film.FilmRepository$FullNeedRow(f.id, f.title, f.year, f.tmdbId, "
            + "f.originalLanguage, f.spokenLanguages, f.languagesFetchedAt, f.fullNeedOwner) from Film f "
            + "order by f.sortTitle, f.id")
    List<FullNeedRow> findFullNeedRows();

    /** When ratings were last fetched for any film, or null when never. */
    @org.springframework.data.jpa.repository.Query("select max(f.ratingsFetchedAt) from Film f")
    java.time.Instant findLatestRatingsFetchedAt();
}
