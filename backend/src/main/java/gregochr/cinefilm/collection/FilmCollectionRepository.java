package gregochr.cinefilm.collection;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link FilmCollection}. */
public interface FilmCollectionRepository extends JpaRepository<FilmCollection, Long> {
}
