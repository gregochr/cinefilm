package gregochr.cinefilm.film;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link GroupingChange}. */
public interface GroupingChangeRepository extends JpaRepository<GroupingChange, Long> {

    /** The newest 20 changes, newest first. */
    List<GroupingChange> findTop20ByOrderByHappenedAtDescIdDesc();
}
