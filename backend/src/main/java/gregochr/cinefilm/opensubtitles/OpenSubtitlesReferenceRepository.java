package gregochr.cinefilm.opensubtitles;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     */
    record ReferenceFacts(Long id, Long filmId, String imdbId, long osFileId, String release, int cueCount,
                          Long lastCueEndMs) {
    }

    /** The facts of every reference stored for some films, for the timing check on the playback path. */
    @Query("select new gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository$ReferenceFacts(r.id, "
        + "r.filmId, r.imdbId, r.osFileId, r.release, r.cueCount, r.lastCueEndMs) from OpenSubtitlesReference r "
        + "where r.filmId in :filmIds")
    List<ReferenceFacts> findFactsByFilmIdIn(@Param("filmIds") Collection<Long> filmIds);

    /** Every reference stored for some films (any IMDb id; the caller keeps those for the film's current one). */
    List<OpenSubtitlesReference> findByFilmIdIn(Collection<Long> filmIds);
}
