package gregochr.cinefilm.film;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Persistence for {@link MediaFile}. */
public interface MediaFileRepository extends JpaRepository<MediaFile, Long> {

    /**
     * What the scanner needs to know about a stored file.
     *
     * @param id media file id
     * @param path path relative to the library root
     * @param sizeBytes size at the last scan, or null
     * @param modifiedAt last-modified time at the last scan, or null
     * @param presence whether the last scan found it
     * @param playability whether it can be played
     */
    record Known(Long id, String path, Long sizeBytes, Instant modifiedAt, Presence presence,
                 Playability playability) {
    }

    /** Every original file, as the scanner's snapshot of what is already in the library. */
    @Query("select new gregochr.cinefilm.film.MediaFileRepository$Known(m.id, m.path, m.sizeBytes, m.modifiedAt, "
        + "m.presence, m.playability) from MediaFile m where m.kind = gregochr.cinefilm.film.MediaKind.ORIGINAL")
    List<Known> findKnownOriginals();

    /** Original files that were last seen with the given presence and playability. */
    @Query("select m from MediaFile m where m.kind = gregochr.cinefilm.film.MediaKind.ORIGINAL "
        + "and m.presence = :presence and m.playability = :playability order by m.path")
    List<MediaFile> findOriginals(Presence presence, Playability playability);

    /** Original files with the given presence. */
    @Query("select m from MediaFile m where m.kind = gregochr.cinefilm.film.MediaKind.ORIGINAL "
        + "and m.presence = :presence order by m.path")
    List<MediaFile> findOriginals(Presence presence);

    /** How many original files were last seen with the given presence. */
    @Query("select count(m) from MediaFile m where m.kind = gregochr.cinefilm.film.MediaKind.ORIGINAL "
        + "and m.presence = :presence")
    long countOriginals(Presence presence);

    /** How many original files are present with the given playability. */
    @Query("select count(m) from MediaFile m where m.kind = gregochr.cinefilm.film.MediaKind.ORIGINAL "
        + "and m.presence = gregochr.cinefilm.film.Presence.PRESENT and m.playability = :playability")
    long countPresent(Playability playability);
}
