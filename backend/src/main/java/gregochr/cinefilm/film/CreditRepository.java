package gregochr.cinefilm.film;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link Credit}. */
public interface CreditRepository extends JpaRepository<Credit, Long> {

    /** Every credit held by the person, in any film. */
    List<Credit> findByPersonId(Long personId);
}
