package gregochr.cinefilm.plex;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link PlexImportRun}. */
public interface PlexImportRunRepository extends JpaRepository<PlexImportRun, Long> {

    /** The most recent import that has finished (successfully or not). */
    Optional<PlexImportRun> findFirstByFinishedAtNotNullOrderByIdDesc();
}
