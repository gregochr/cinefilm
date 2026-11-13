package gregochr.cinefilm.subtitle;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link ForcedDownloadBlock}: blocks are per {@link SubtitleKind}. */
public interface ForcedDownloadBlockRepository extends JpaRepository<ForcedDownloadBlock, Long> {

    /** The files of one kind removed from some editions. */
    List<ForcedDownloadBlock> findByEditionIdInAndKind(Collection<Long> editionIds, SubtitleKind kind);

    /** True when the owner removed this file from the edition for this IMDb id, as this kind. */
    boolean existsByEditionIdAndImdbIdAndOsFileIdAndKind(Long editionId, String imdbId, long osFileId,
                                                         SubtitleKind kind);
}
