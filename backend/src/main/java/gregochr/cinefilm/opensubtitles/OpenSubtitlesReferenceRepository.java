package gregochr.cinefilm.opensubtitles;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link OpenSubtitlesReference}. */
public interface OpenSubtitlesReferenceRepository extends JpaRepository<OpenSubtitlesReference, Long> {

    /** The reference already downloaded for a film's OpenSubtitles file, if any. */
    Optional<OpenSubtitlesReference> findByFilmIdAndOsFileId(Long filmId, long osFileId);
}
