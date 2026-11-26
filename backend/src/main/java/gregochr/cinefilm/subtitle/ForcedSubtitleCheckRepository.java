package gregochr.cinefilm.subtitle;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link ForcedSubtitleCheck}: one current row per edition. */
public interface ForcedSubtitleCheckRepository extends JpaRepository<ForcedSubtitleCheck, Long> {

    /** The edition's current check, if it has one. */
    Optional<ForcedSubtitleCheck> findByEditionId(Long editionId);

    /** The current checks of some editions. */
    List<ForcedSubtitleCheck> findByEditionIdIn(Collection<Long> editionIds);

    /** The checks made against one reference (any edition of its film). */
    List<ForcedSubtitleCheck> findByReferenceId(Long referenceId);

    /** The outdated checks (those with {@code staleSince} set) of some editions. */
    List<ForcedSubtitleCheck> findByEditionIdInAndStaleSinceIsNotNull(Collection<Long> editionIds);
}
