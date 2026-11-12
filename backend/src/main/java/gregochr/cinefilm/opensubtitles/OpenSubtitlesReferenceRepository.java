package gregochr.cinefilm.opensubtitles;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link OpenSubtitlesReference}. */
public interface OpenSubtitlesReferenceRepository extends JpaRepository<OpenSubtitlesReference, Long> {

    /** The reference already downloaded for a film's OpenSubtitles file under one IMDb id, if any. */
    Optional<OpenSubtitlesReference> findByFilmIdAndImdbIdAndOsFileId(Long filmId, String imdbId, long osFileId);
}
