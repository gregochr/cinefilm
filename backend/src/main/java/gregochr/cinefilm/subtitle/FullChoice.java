package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Pick;
import java.util.Collection;
import java.util.List;

/**
 * Which full English subtitle an edition plays: the owner's file when it has one, else a full download. A present
 * owner's full file always beats a download, and so does a conflict between the owner's files (the owner is to sort
 * that out, nothing else plays meanwhile). A download plays unchecked (decision 66: a foreign film is unwatchable
 * without it). Only full rows are ever looked at. Pure.
 *
 * @param pick what plays; no slots when nothing does (its conflicting ids are the owner's files in conflict)
 * @param download true when the pick is a download, false for the owner's files (or nothing)
 */
record FullChoice(Pick pick, boolean download) {

    /**
     * Chooses what an edition plays.
     *
     * @param edition the edition (its film loaded, for the IMDb id a download must have been fetched for)
     * @param found full sidecars attached to some film files (others are ignored)
     * @return the choice
     */
    static FullChoice of(Edition edition, Collection<SubtitleSidecar> found) {
        List<Long> partIds = edition.originalParts().stream().map(MediaFile::getId).toList();
        List<SubtitleSidecar> rows = List.copyOf(found);
        Pick owner = SidecarPrecedence.pick(partIds, rows, SubtitleKind.FULL);
        if (!owner.slots().isEmpty() || !owner.conflicting().isEmpty()) {
            return new FullChoice(owner, false);
        }
        List<SubtitleSidecar> downloads = Downloads.of(edition, partIds, rows, SubtitleKind.FULL);
        return downloads.isEmpty() ? new FullChoice(owner, false)
            : new FullChoice(Downloads.pick(downloads.get(0)), true);
    }
}
