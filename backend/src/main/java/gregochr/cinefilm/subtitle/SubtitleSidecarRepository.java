package gregochr.cinefilm.subtitle;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Persistence for {@link SubtitleSidecar}. */
public interface SubtitleSidecarRepository extends JpaRepository<SubtitleSidecar, Long> {

    /** Every sidecar attached to one of the given film files. */
    List<SubtitleSidecar> findByMediaFileIdIn(Collection<Long> mediaFileIds);

    /** Every sidecar, in path order. */
    List<SubtitleSidecar> findAllByOrderByPathAscIdAsc();

    /**
     * Ids of the films with a present sidecar attached to a file of one of their editions. A missing sidecar, an
     * unmatched one and one attached to another film's file do not count.
     */
    @Query("select distinct e.film.id from SubtitleSidecar s join MediaFile m on m.id = s.mediaFileId "
        + "join m.edition e where s.presence = gregochr.cinefilm.film.Presence.PRESENT")
    List<Long> findFilmIdsWithPresentSidecar();

    /** How many present sidecars are attached to files of the film's editions. */
    @Query("select count(s) from SubtitleSidecar s join MediaFile m on m.id = s.mediaFileId "
        + "join m.edition e where e.film.id = :filmId and s.presence = gregochr.cinefilm.film.Presence.PRESENT")
    long countPresentForFilm(Long filmId);
}
