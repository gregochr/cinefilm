package gregochr.cinefilm.subtitle;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link ForcedCheckCandidate}: the ledger is per {@link SubtitleKind}. */
public interface ForcedCheckCandidateRepository extends JpaRepository<ForcedCheckCandidate, Long> {

    /** The candidates of one kind that some editions have been compared with. */
    List<ForcedCheckCandidate> findByEditionIdInAndKind(Collection<Long> editionIds, SubtitleKind kind);

    /** True when the edition has already been compared with this file for this IMDb id, for this kind. */
    boolean existsByEditionIdAndImdbIdAndOsFileIdAndKind(Long editionId, String imdbId, long osFileId,
                                                         SubtitleKind kind);
}
