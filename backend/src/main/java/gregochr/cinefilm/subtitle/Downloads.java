package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.ForcedNeedChoice;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Pick;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Slot;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Which of an edition's downloaded subtitles count. A download is tied to an edition like a whole-edition file: it is
 * stored against one of the edition's part files and timed for the whole edition. It counts only while it is present
 * with at least one cue and was fetched for the film's current IMDb id (the cleanup on an IMDb change removes the
 * others; this keeps a race with it from playing the wrong film's subtitle). A film the owner marked "not needed"
 * serves no download at all: a download is CineFilm's own addition, and the owner has said the film wants none (the
 * owner's own files and the film's embedded track are theirs and still play). Pure.
 */
final class Downloads {

    private Downloads() {
    }

    /**
     * True when the download was made for this edition: its made-up path names the edition, so a download that followed
     * its part file into another edition (an edit joined, split or reassigned the parts) does not count there.
     */
    static boolean madeFor(Edition edition, SubtitleSidecar download) {
        return download.getPath().equals("opensubtitles:" + edition.getId() + ":" + download.getOpenSubtitlesFileId());
    }

    /** The edition a download was made for, read from its made-up path (not from where its file is now). */
    static Long madeForEditionId(SubtitleSidecar download) {
        String path = download.getPath();
        return Long.valueOf(path.substring(path.indexOf(':') + 1, path.lastIndexOf(':')));
    }

    /**
     * The downloads that count for an edition, lowest id first.
     *
     * @param edition the edition (its film loaded)
     * @param partIds the ids of its part files
     * @param found sidecars of any kind attached to some film files (others are ignored)
     * @return the edition's live downloads
     */
    static List<SubtitleSidecar> of(Edition edition, List<Long> partIds, Collection<SubtitleSidecar> found) {
        String imdbId = edition.getFilm().getImdbId();
        if (isNotNeeded(edition)) {
            return List.of();
        }
        return found.stream()
            .filter(s -> s.isDownload() && s.getPresence() == Presence.PRESENT && s.getCueCount() > 0
                && partIds.contains(s.getMediaFileId()) && madeFor(edition, s) && imdbId != null
                && imdbId.equals(s.getImdbId()))
            .sorted(Comparator.comparing(SubtitleSidecar::getId)).toList();
    }

    /** True when the owner marked the edition's film as not needing forced subtitles. */
    static boolean isNotNeeded(Edition edition) {
        return edition.getFilm().getForcedNeedOwner() == ForcedNeedChoice.NOT_NEEDED;
    }

    /** A download as the one file an edition would play (timed from the start of the edition). */
    static Pick pick(SubtitleSidecar download) {
        return new Pick(List.of(new Slot(download, 0)), Set.of());
    }
}
