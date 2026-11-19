package gregochr.cinefilm.film;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link ChristmasOverride}. */
public interface ChristmasOverrideRepository extends JpaRepository<ChristmasOverride, Long> {

    /** Every override with this choice. */
    List<ChristmasOverride> findByChoice(ChristmasChoice choice);
}
