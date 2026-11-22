package gregochr.cinefilm.film;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
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
     * Every edition with its files (an edition with no file row too: a left join) and its film loaded in one statement,
     * for the Admin counts that apply the browse rule ({@code Film#browsable}) to the whole library. A film with no
     * edition at all is absent. There is no {@code distinct}: Hibernate 6 returns each root entity once from a
     * fetch join (proved by {@code EditionListIntegrationTest}), and {@code distinct} made PostgreSQL sort the whole
     * library on each read.
     */
    @Query("select e from Edition e left join fetch e.mediaFiles join fetch e.film")
    List<Edition> findAllWithFilesIncludingEmpty();

    /** One edition with its film loaded (and no files), for code that needs the film's id, title or IMDb id. */
    @Query("select e from Edition e join fetch e.film where e.id = :id")
    Optional<Edition> findWithFilmById(Long id);

    /**
     * Every edition with its files and its film loaded in one statement, for a pass over the whole library that must
     * not issue a query per edition (credits detection: about 1,100 films).
     */
    @Query("select distinct e from Edition e join fetch e.mediaFiles join fetch e.film")
    List<Edition> findAllWithFiles();

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

    /**
     * Every edition with its files (an edition with no file row too) and nothing else loaded: the film stays a lazy
     * reference that is never touched, so no film row (and none of its large text columns) is read. Pair it with
     * {@link #findEditionOwners()} for the film's id. For the Admin summary's polling.
     */
    @Query("select e from Edition e left join fetch e.mediaFiles")
    List<Edition> findAllWithFilesLeavingFilmLazy();

    /**
     * An edition and the film it belongs to.
     *
     * @param editionId the edition
     * @param filmId its film
     * @param editionsConfirmed true when the owner has settled the film's editions
     */
    record EditionOwner(Long editionId, Long filmId, boolean editionsConfirmed) {
    }

    /** Every edition's film id and that film's editions-confirmed flag, as columns (no entity is loaded). */
    @Query("select new gregochr.cinefilm.film.EditionRepository$EditionOwner(e.id, e.film.id, "
        + "e.film.editionsConfirmed) from Edition e")
    List<EditionOwner> findEditionOwners();

    /** Every edition's id in id order, for a pass that reads the editions in batches. */
    @Query("select e.id from Edition e order by e.id")
    List<Long> findAllIds();

    /**
     * The given editions with their files (an edition with no file row too) and nothing else loaded: the film stays
     * a lazy reference that is never touched. One statement however many ids, for the credits apply pass.
     */
    @Query("select e from Edition e left join fetch e.mediaFiles where e.id in :ids")
    List<Edition> findWithFilesByIdIn(Collection<Long> ids);

    /**
     * The edition is not hidden from browse: no original part is missing from disk, unplayable, or (in a multi-part
     * edition) without a stored duration. The database form of {@link Edition#hiddenFromBrowse()}, for an alias
     * {@code e}; the integration test pins the two together.
     */
    String VISIBLE = "not exists (select hm.id from MediaFile hm where hm.edition = e "
        + "and hm.kind = gregochr.cinefilm.film.MediaKind.ORIGINAL "
        + "and (hm.presence <> gregochr.cinefilm.film.Presence.PRESENT "
        + "or hm.playability <> gregochr.cinefilm.film.Playability.PLAYABLE "
        + "or ((hm.durationMs is null or hm.durationMs <= 0) and (select count(sib.id) from MediaFile sib "
        + "where sib.edition = e and sib.kind = gregochr.cinefilm.film.MediaKind.ORIGINAL) > 1)))";

    /**
     * Which credits sources a list filter accepts, for an alias {@code e}: every one ({@code :anySource}), the ones
     * with no source ({@code :unsettled} true) or the one {@code :source} (when {@code :unsettled} is false).
     */
    String SOURCE_MATCHES = "(:anySource = true or (:unsettled = true and e.creditsMarkerSource is null) "
        + "or (:unsettled = false and e.creditsMarkerSource = :source))";

    /**
     * One page of visible edition ids for the Admin End credits list: by the film's sort title (case-insensitive),
     * then its year with nulls last (placed explicitly, as H2 and PostgreSQL sort them differently), then the
     * edition's name, then its id.
     */
    @Query("select e.id from Edition e join e.film f where " + VISIBLE + " and " + SOURCE_MATCHES
        + " order by lower(f.sortTitle), case when f.year is null then 1 else 0 end, f.year, e.name, e.id")
    List<Long> findCreditsPageIds(boolean anySource, boolean unsettled, CreditsMarkerSource source,
                                  Pageable pageable);

    /**
     * One edition's film and the film's unit key inputs, as L1's export reads them.
     *
     * @param editionId the edition
     * @param filmId the edition's film
     * @param filmTmdbId the film's TMDB id; null when not matched
     * @param title the film's title
     * @param year the film's release year; null when unknown
     * @param collectionId the film's collection; null when none
     * @param collectionTmdbId the collection's TMDB id; null when none
     */
    record EditionFilmRow(Long editionId, Long filmId, Long filmTmdbId, String title, Integer year,
                          Long collectionId, Long collectionTmdbId) {
    }

    /**
     * The film and unit key inputs of each given edition, in one statement, without loading any entity (the credits
     * pass leaves the film lazy).
     */
    @Query("select new gregochr.cinefilm.film.EditionRepository$EditionFilmRow(e.id, f.id, f.tmdbId, f.title, "
        + "f.year, c.id, c.tmdbId) from Edition e join e.film f left join f.collection c where e.id in :ids")
    List<EditionFilmRow> findFilmRowsByIdIn(Collection<Long> ids);

    /**
     * The given editions with their files (an edition with no original file row too) and their film, in one
     * statement. The film is fetched, unlike {@link #findWithFilesByIdIn}, which leaves it lazy.
     */
    @Query("select e from Edition e left join fetch e.mediaFiles join fetch e.film where e.id in :ids")
    List<Edition> findWithFilesAndFilmByIdIn(Collection<Long> ids);

    /**
     * How many visible editions a film has.
     *
     * @param filmId the film
     * @param count its visible editions
     */
    record FilmEditionCount(Long filmId, long count) {
    }

    /** The visible-edition count of each given film, in one grouped statement. */
    @Query("select new gregochr.cinefilm.film.EditionRepository$FilmEditionCount(e.film.id, count(e)) "
        + "from Edition e where e.film.id in :filmIds and " + VISIBLE + " group by e.film.id")
    List<FilmEditionCount> countVisibleByFilmIdIn(Collection<Long> filmIds);

    /**
     * How many visible editions have a credits source.
     *
     * @param source the source; null for editions with none
     * @param count how many
     */
    record SourceCount(CreditsMarkerSource source, long count) {
    }

    /** The visible editions grouped by credits source (one row for no source), in one statement. */
    @Query("select new gregochr.cinefilm.film.EditionRepository$SourceCount(e.creditsMarkerSource, count(e)) "
        + "from Edition e where " + VISIBLE + " group by e.creditsMarkerSource")
    List<SourceCount> countVisibleBySource();
}
