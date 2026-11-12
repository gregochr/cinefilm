package gregochr.cinefilm.opensubtitles;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link OpenSubtitlesReference}. */
public interface OpenSubtitlesReferenceRepository extends JpaRepository<OpenSubtitlesReference, Long> {

    /** The reference already downloaded for a film's OpenSubtitles file under one IMDb id, if any. */
    Optional<OpenSubtitlesReference> findByFilmIdAndImdbIdAndOsFileId(Long filmId, String imdbId, long osFileId);

    /** Every reference stored for some films (any IMDb id; the caller keeps those for the film's current one). */
    List<OpenSubtitlesReference> findByFilmIdIn(Collection<Long> filmIds);
}
