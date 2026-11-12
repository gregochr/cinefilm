package gregochr.cinefilm.subtitle;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link ForcedCheckCandidate}. */
public interface ForcedCheckCandidateRepository extends JpaRepository<ForcedCheckCandidate, Long> {

    /** The candidates some editions have been compared with. */
    List<ForcedCheckCandidate> findByEditionIdIn(Collection<Long> editionIds);

    /** True when the edition has already been compared with this file for this IMDb id. */
    boolean existsByEditionIdAndImdbIdAndOsFileId(Long editionId, String imdbId, long osFileId);
}
