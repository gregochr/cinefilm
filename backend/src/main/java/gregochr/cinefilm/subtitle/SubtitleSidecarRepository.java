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
     * Ids of the films with a present sidecar with at least one cue attached to a present file of one of
     * their editions (evidence that the film needs forced subtitles, whether or not playback can serve them). A
     * missing sidecar, an empty one, an unmatched one, one attached to another film's file and one on a missing file
     * do not count.
     */
    @Query("select distinct e.film.id from SubtitleSidecar s join MediaFile m on m.id = s.mediaFileId "
        + "join m.edition e where s.presence = gregochr.cinefilm.film.Presence.PRESENT "
        + "and s.cueCount > 0 and m.presence = gregochr.cinefilm.film.Presence.PRESENT")
    List<Long> findFilmIdsWithPresentSidecar();

    /** How many present sidecars with cues are attached to files of the film's editions. */
    @Query("select count(s) from SubtitleSidecar s join MediaFile m on m.id = s.mediaFileId "
        + "join m.edition e where e.film.id = :filmId and s.presence = gregochr.cinefilm.film.Presence.PRESENT "
        + "and s.cueCount > 0 and m.presence = gregochr.cinefilm.film.Presence.PRESENT")
    long countPresentForFilm(Long filmId);
}
