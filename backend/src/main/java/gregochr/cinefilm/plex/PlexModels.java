package gregochr.cinefilm.plex;

import java.time.Instant;
import java.util.List;

/** The parts of Plex's responses CineFilm uses. Plex text is untrusted: it is only ever stored and shown as text. */
public final class PlexModels {

    private PlexModels() {
    }

    /**
     * One film of a Plex movie library, from {@code /library/sections/{id}/all?includeGuids=1}.
     *
     * @param ratingKey Plex's id for the film (only used in the report, never stored)
     * @param title the title
     * @param titleSort Plex's sort title, or null
     * @param year release year, or null
     * @param summary the synopsis, or null
     * @param editionTitle the edition name ("Director's Cut"), or null
     * @param addedAt when Plex added it, or null
     * @param updatedAt when Plex last changed it, or null
     * @param thumb Plex's poster path, or null
     * @param art Plex's backdrop path, or null
     * @param tmdbId the id of its {@code tmdb://} guid, or null
     * @param viewCount how many times it was watched
     * @param viewOffsetMs the resume position in milliseconds (0 when none)
     * @param lastViewedAt when it was last watched, or null
     * @param files the file paths of its parts, in the order Plex lists them
     */
    public record PlexFilm(String ratingKey, String title, String titleSort, Integer year, String summary,
                           String editionTitle, Instant addedAt, Instant updatedAt, String thumb, String art,
                           Long tmdbId, int viewCount, long viewOffsetMs, Instant lastViewedAt,
                           List<String> files) {
    }

    /**
     * An image Plex resized for us.
     *
     * @param bytes the image
     * @param contentType the response content type
     */
    public record PlexImage(byte[] bytes, String contentType) {
    }
}
