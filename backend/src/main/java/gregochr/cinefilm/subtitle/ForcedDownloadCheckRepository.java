package gregochr.cinefilm.subtitle;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link ForcedDownloadCheck}: one current row per downloaded subtitle. */
public interface ForcedDownloadCheckRepository extends JpaRepository<ForcedDownloadCheck, Long> {

    /** The download's current check, if it has one. */
    Optional<ForcedDownloadCheck> findBySidecarId(Long sidecarId);

    /** The current checks of some downloads. */
    List<ForcedDownloadCheck> findBySidecarIdIn(Collection<Long> sidecarIds);
}
