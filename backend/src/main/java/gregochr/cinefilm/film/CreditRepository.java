package gregochr.cinefilm.film;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link Credit}. */
public interface CreditRepository extends JpaRepository<Credit, Long> {

    List<Credit> findByPersonId(Long personId);
}
