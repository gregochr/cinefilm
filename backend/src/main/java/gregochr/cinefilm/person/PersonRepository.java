package gregochr.cinefilm.person;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link Person}. */
public interface PersonRepository extends JpaRepository<Person, Long> {

    /** The person with a TMDB id, if known (the first when several exist). */
    Optional<Person> findFirstByTmdbIdOrderByIdAsc(Long tmdbId);
}
