package gregochr.cinefilm.subtitle;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link SubtitleSidecar}. Every read takes the {@link SubtitleKind} it wants, so a forced path can
 * never see a full file and the compiler finds every caller ({@code findAll()} is only for the scan, which tracks the
 * files on disk of both kinds).
 */
public interface SubtitleSidecarRepository extends JpaRepository<SubtitleSidecar, Long> {

    /** Every sidecar of one kind attached to one of the given film files. */
    List<SubtitleSidecar> findByMediaFileIdInAndKind(Collection<Long> mediaFileIds, SubtitleKind kind);

    /** Every sidecar of one kind, in path order. */
    List<SubtitleSidecar> findAllByKindOrderByPathAscIdAsc(SubtitleKind kind);

    /**
     * Ids of the films with a present owner's file ({@code .srt} beside the film, not a download) of this kind with at
     * least one cue attached to a present file of one of their editions (evidence that the film needs forced
     * subtitles, whether or not playback can serve them). A missing sidecar, an empty one, an unmatched one, one
     * attached to another film's file, one on a missing file and a download do not count.
     */
    @Query("select distinct e.film.id from SubtitleSidecar s join MediaFile m on m.id = s.mediaFileId "
        + "join m.edition e where s.presence = gregochr.cinefilm.film.Presence.PRESENT and s.kind = :kind "
        + "and s.origin = gregochr.cinefilm.subtitle.SubtitleSidecar.Origin.FILE "
        + "and s.cueCount > 0 and m.presence = gregochr.cinefilm.film.Presence.PRESENT")
    List<Long> findFilmIdsWithPresentSidecar(@Param("kind") SubtitleKind kind);

    /**
     * Ids of the films with a present subtitle of this kind (an owner's file or a download) with at least one cue on a
     * present file of one of their editions: the films whose subtitles are worth checking and timing.
     */
    @Query("select distinct e.film.id from SubtitleSidecar s join MediaFile m on m.id = s.mediaFileId "
        + "join m.edition e where s.presence = gregochr.cinefilm.film.Presence.PRESENT and s.kind = :kind "
        + "and s.cueCount > 0 and m.presence = gregochr.cinefilm.film.Presence.PRESENT")
    List<Long> findFilmIdsWithPresentSubtitle(@Param("kind") SubtitleKind kind);

    /** How many present owner's files of this kind with cues are attached to files of the film's editions. */
    @Query("select count(s) from SubtitleSidecar s join MediaFile m on m.id = s.mediaFileId "
        + "join m.edition e where e.film.id = :filmId and s.presence = gregochr.cinefilm.film.Presence.PRESENT "
        + "and s.kind = :kind and s.origin = gregochr.cinefilm.subtitle.SubtitleSidecar.Origin.FILE "
        + "and s.cueCount > 0 and m.presence = gregochr.cinefilm.film.Presence.PRESENT")
    long countPresentForFilm(@Param("filmId") Long filmId, @Param("kind") SubtitleKind kind);

    /** How many present subtitles of this kind (owner's files and downloads) with cues the film's files have. */
    @Query("select count(s) from SubtitleSidecar s join MediaFile m on m.id = s.mediaFileId "
        + "join m.edition e where e.film.id = :filmId and s.presence = gregochr.cinefilm.film.Presence.PRESENT "
        + "and s.kind = :kind and s.cueCount > 0 and m.presence = gregochr.cinefilm.film.Presence.PRESENT")
    long countPresentSubtitlesForFilm(@Param("filmId") Long filmId, @Param("kind") SubtitleKind kind);
}
