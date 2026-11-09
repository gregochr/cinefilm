package gregochr.cinefilm.transcode;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Persistence for {@link MediaKeyframes}. */
public interface MediaKeyframesRepository extends JpaRepository<MediaKeyframes, Long> {

    /** How many of the library's present, playable original files already have an index. */
    @Query("select count(k) from MediaKeyframes k, MediaFile m where m.id = k.mediaFileId "
        + "and m.kind = gregochr.cinefilm.film.MediaKind.ORIGINAL "
        + "and m.presence = gregochr.cinefilm.film.Presence.PRESENT "
        + "and m.playability = gregochr.cinefilm.film.Playability.PLAYABLE")
    long countIndexedPlayable();
}
