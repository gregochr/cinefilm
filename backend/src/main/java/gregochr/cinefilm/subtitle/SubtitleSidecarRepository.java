package gregochr.cinefilm.subtitle;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link SubtitleSidecar}. */
public interface SubtitleSidecarRepository extends JpaRepository<SubtitleSidecar, Long> {

    /** Every sidecar attached to one of the given film files. */
    List<SubtitleSidecar> findByMediaFileIdIn(Collection<Long> mediaFileIds);

    /** Every sidecar, in path order. */
    List<SubtitleSidecar> findAllByOrderByPathAscIdAsc();
}
