package gregochr.cinefilm.subtitle;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link ForcedSubtitleAlignment}: one current row per sidecar. */
public interface ForcedSubtitleAlignmentRepository extends JpaRepository<ForcedSubtitleAlignment, Long> {

    /** The sidecar's current alignment, if it has one. */
    Optional<ForcedSubtitleAlignment> findBySidecarId(Long sidecarId);

    /** The current alignments of some sidecars. */
    List<ForcedSubtitleAlignment> findBySidecarIdIn(Collection<Long> sidecarIds);
}
