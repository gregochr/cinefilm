package gregochr.cinefilm.library;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link ScanRun}. */
public interface ScanRunRepository extends JpaRepository<ScanRun, Long> {

    /** The most recent scan that has finished (successfully or not). */
    Optional<ScanRun> findFirstByFinishedAtNotNullOrderByIdDesc();

    /** True once any scan has finished without an error: the Plex import needs one (the scan owns the files). */
    boolean existsByFinishedAtNotNullAndErrorIsNull();
}
