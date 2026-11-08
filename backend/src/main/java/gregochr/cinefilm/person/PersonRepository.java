package gregochr.cinefilm.person;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link Person}. */
public interface PersonRepository extends JpaRepository<Person, Long> {
}
