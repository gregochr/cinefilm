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
}
