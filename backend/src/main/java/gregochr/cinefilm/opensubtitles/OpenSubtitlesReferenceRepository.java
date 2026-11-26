package gregochr.cinefilm.opensubtitles;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Persistence for {@link OpenSubtitlesReference}. */
public interface OpenSubtitlesReferenceRepository extends JpaRepository<OpenSubtitlesReference, Long> {

    /** The reference already downloaded for a film's OpenSubtitles file under one IMDb id, if any. */
    Optional<OpenSubtitlesReference> findByFilmIdAndImdbIdAndOsFileId(Long filmId, String imdbId, long osFileId);

    /**
     * What judging a reference needs, without its cue text (up to 1 MB each).
     *
     * @param id the row id
     * @param filmId the film
     * @param imdbId the IMDb id it was fetched for
     * @param osFileId the OpenSubtitles file id
     * @param release its release name, or null
     * @param cueCount its line count (0 when unusable)
     * @param lastCueEndMs where its last cue ends, or null for a file stored before that was kept
     * @param timedFps the rate OpenSubtitles states for it, or null
     * @param cuesVersion the version of its cues when read ({@link OpenSubtitlesReference#getCuesVersion})
     */
    record ReferenceFacts(Long id, Long filmId, String imdbId, long osFileId, String release, int cueCount,
                          Long lastCueEndMs, Double timedFps, int cuesVersion) {

        /** Facts of cues that were never changed (version 0). */
        public ReferenceFacts(Long id, Long filmId, String imdbId, long osFileId, String release, int cueCount,
                              Long lastCueEndMs, Double timedFps) {
            this(id, filmId, imdbId, osFileId, release, cueCount, lastCueEndMs, timedFps, 0);
        }
    }

    /** The facts of every reference stored for some films, for the timing check on the playback path. */
    @Query("select new gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository$ReferenceFacts(r.id, "
        + "r.filmId, r.imdbId, r.osFileId, r.release, r.cueCount, r.lastCueEndMs, r.timedFps, r.cuesVersion) "
        + "from OpenSubtitlesReference r "
        + "where r.filmId in :filmIds")
    List<ReferenceFacts> findFactsByFilmIdIn(@Param("filmIds") Collection<Long> filmIds);

    /** Every reference stored for some films (any IMDb id; the caller keeps those for the film's current one). */
    List<OpenSubtitlesReference> findByFilmIdIn(Collection<Long> filmIds);

    /** The version of a reference's cues now (empty when the row is gone). */
    @Query("select r.cuesVersion from OpenSubtitlesReference r where r.id = :id")
    Optional<Integer> findCuesVersionById(@Param("id") Long id);

    /** The ids of the usable references the retime job visits: one with a stated rate, and one that is retimed. */
    @Query("select r.id from OpenSubtitlesReference r where r.cueCount > 0 "
        + "and (r.timedFps is not null or r.retimedBy is not null) order by r.id")
    List<Long> findRetimeCandidateIds();

    /**
     * Sets the stated frame rate of a reference that has none, and touches no other column, so it cannot write back
     * a value another writer changed meanwhile. A reference that already has a rate is left alone.
     *
     * @param id the row id
     * @param fps the rate OpenSubtitles states
     * @return 1 when the rate was filled, 0 when the row already had one (or is gone)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("update OpenSubtitlesReference r set r.timedFps = :fps where r.id = :id and r.timedFps is null")
    int fillTimedFps(@Param("id") long id, @Param("fps") double fps);
}
