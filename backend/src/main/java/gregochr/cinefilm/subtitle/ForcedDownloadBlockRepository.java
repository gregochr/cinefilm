package gregochr.cinefilm.subtitle;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link ForcedDownloadBlock}. */
public interface ForcedDownloadBlockRepository extends JpaRepository<ForcedDownloadBlock, Long> {

    /** The files removed from some editions. */
    List<ForcedDownloadBlock> findByEditionIdIn(Collection<Long> editionIds);

    /** True when the owner removed this file from the edition for this IMDb id. */
    boolean existsByEditionIdAndImdbIdAndOsFileId(Long editionId, String imdbId, long osFileId);
}
