package gregochr.cinefilm.collection;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link FilmCollection}. */
public interface FilmCollectionRepository extends JpaRepository<FilmCollection, Long> {

    /** The collection with a TMDB id, if known (the first when several exist). */
    Optional<FilmCollection> findFirstByTmdbIdOrderByIdAsc(Long tmdbId);

    /** Collections whose parts still have to be fetched from TMDB. */
    java.util.List<FilmCollection> findByPartsCompleteFalseOrderById();
}
