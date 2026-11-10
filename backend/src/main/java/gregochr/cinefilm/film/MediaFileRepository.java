package gregochr.cinefilm.film;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
     * @param pixelFormat the stored pixel format: null when the file has never been probed since the column was
     *     added (the scanner re-probes it once), empty when it was probed and ffprobe gave none
     */
    record Known(Long id, String path, Long sizeBytes, Instant modifiedAt, Presence presence,
                 Playability playability, String pixelFormat) {
    }

    /** Every original file, as the scanner's snapshot of what is already in the library. */
    @Query("select new gregochr.cinefilm.film.MediaFileRepository$Known(m.id, m.path, m.sizeBytes, m.modifiedAt, "
        + "m.presence, m.playability, m.pixelFormat) from MediaFile m "
        + "where m.kind = gregochr.cinefilm.film.MediaKind.ORIGINAL")
    List<Known> findKnownOriginals();

    /**
     * Every original file with its edition and film loaded in one query, for the grouping passes (which read the
     * whole library and must not issue a query per file).
     */
    @Query("select m from MediaFile m join fetch m.edition e join fetch e.film "
        + "where m.kind = gregochr.cinefilm.film.MediaKind.ORIGINAL order by m.path, m.id")
    List<MediaFile> findAllOriginalsWithEdition();

    /**
     * Original files whose path starts with the given prefix ({@code "folder/%"}): the candidates for "same folder"
     * (callers compare the exact folder themselves, so a {@code _} or {@code %} in a folder name only over-matches).
     */
    @Query("select m from MediaFile m join fetch m.edition e join fetch e.film "
        + "where m.kind = gregochr.cinefilm.film.MediaKind.ORIGINAL and m.path like :prefix order by m.id")
    List<MediaFile> findOriginalsUnder(String prefix);

    /** Original files that sit directly in the library root (their path has no folder). */
    @Query("select m from MediaFile m join fetch m.edition e join fetch e.film "
        + "where m.kind = gregochr.cinefilm.film.MediaKind.ORIGINAL and m.path not like '%/%' order by m.id")
    List<MediaFile> findRootOriginals();

    /**
     * Moves files to another edition in one statement, keeping each file's id (and so its streams and keyframe
     * index). The persistence context is flushed first and cleared afterwards: reload entities before using them.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update MediaFile m set m.edition = :target where m.id in :ids")
    int moveToEdition(Collection<Long> ids, Edition target);

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
