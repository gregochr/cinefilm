package gregochr.cinefilm.film;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link FilmPreference}. */
public interface FilmPreferenceRepository extends JpaRepository<FilmPreference, Long> {
}
