package gregochr.cinefilm.film;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link Edition}. */
public interface EditionRepository extends JpaRepository<Edition, Long> {
}
