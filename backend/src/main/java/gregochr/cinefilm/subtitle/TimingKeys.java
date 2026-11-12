package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionTimeline;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.web.JsonEtag;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The names that say what a timing check was made of: the raw sidecar file, and the film files' embedded tracks it was
 * fitted to. A check is current while both still match. Neither includes the sidecar's offset (the fit is of the raw
 * file, so setting the fitted offset does not make it stale). Pure.
 */
final class TimingKeys {

    private TimingKeys() {
    }

    /**
     * A film file's change marker: its size and modification time as last scanned (what a rescan uses to tell a file
     * changed). A cached read of its embedded track is valid while this is the same.
     *
     * @param file the film file
     * @return {@code size:modifiedAtMillis}, or null when either is unknown (the file cannot be told apart from a
     *     changed one, so it is not read)
     */
    static String fingerprint(MediaFile file) {
        if (file.getSizeBytes() == null || file.getModifiedAt() == null) {
            return null;
        }
        return file.getSizeBytes() + ":" + file.getModifiedAt().toEpochMilli();
    }

    /**
     * The stored sidecar as a check sees it: the film file it is attached to, whether it is timed for a whole split
     * edition, and the first 16 hex characters of the SHA-256 of its cues. Moving it or changing its cues changes this.
     */
    static String identity(SubtitleSidecar sidecar) {
        String tag = JsonEtag.etag(sidecar.getCuesText().getBytes(StandardCharsets.UTF_8));
        return sidecar.getMediaFileId() + "/" + (sidecar.isWholeEdition() ? 1 : 0) + "/" + tag.substring(3, 3 + 16);
    }

    /**
     * The film files whose embedded tracks a sidecar is fitted to: every part for a file timed for the whole edition,
     * else the one file it is attached to.
     *
     * @return the files in play order; empty when the sidecar's file is not one of the edition's originals or a file
     *     needed is not present
     */
    static List<MediaFile> referenceFiles(Edition edition, SubtitleSidecar sidecar) {
        List<MediaFile> parts = edition.originalParts();
        List<MediaFile> files = sidecar.isWholeEdition() ? parts
            : parts.stream().filter(p -> p.getId().equals(sidecar.getMediaFileId())).toList();
        return files.stream().anyMatch(f -> f.getPresence() != Presence.PRESENT) ? List.of() : files;
    }

    /** The edition's timeline, or null when a part's length is unknown (so where a later part starts is not). */
    static EditionTimeline timelineOrNull(Edition edition) {
        try {
            return EditionTimeline.of(edition);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * What a sidecar would be fitted to: each reference file's id and fingerprint (and, for a whole-edition file, where
     * that part starts on the edition timeline), as long as the cache has a read of each file as it is now.
     *
     * @param edition the sidecar's edition (its files loaded)
     * @param sidecar the sidecar
     * @param cache the cached reads by media file id
     * @return the key, or null when it cannot be worked out: no reference file, a file with no fingerprint or no
     *     current read yet, or a whole-edition file whose part lengths are unknown
     */
    static String referenceKey(Edition edition, SubtitleSidecar sidecar, Map<Long, EmbeddedCueStarts> cache) {
        List<MediaFile> files = referenceFiles(edition, sidecar);
        if (files.isEmpty()) {
            return null;
        }
        EditionTimeline timeline = null;
        if (sidecar.isWholeEdition()) {
            timeline = timelineOrNull(edition);
            if (timeline == null) {
                return null;
            }
        }
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MediaFile file = files.get(i);
            String fingerprint = fingerprint(file);
            EmbeddedCueStarts read = cache.get(file.getId());
            if (fingerprint == null || read == null || !fingerprint.equals(read.getFingerprint())) {
                return null;
            }
            entries.add(file.getId() + ":" + fingerprint + (timeline == null ? "" : "@" + timeline.partStartMs(i)));
        }
        return String.join(",", entries);
    }
}
