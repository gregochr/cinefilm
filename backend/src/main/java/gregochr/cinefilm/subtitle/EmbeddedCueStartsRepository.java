package gregochr.cinefilm.subtitle;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link EmbeddedCueStarts}: one row per media file, keyed by the media file's id. */
public interface EmbeddedCueStartsRepository extends JpaRepository<EmbeddedCueStarts, Long> {
}
