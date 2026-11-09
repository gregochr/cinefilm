package gregochr.cinefilm.plex;

import gregochr.cinefilm.plex.PlexModels.PlexFilm;
import gregochr.cinefilm.plex.PlexModels.PlexImage;
import java.util.List;

/**
 * What CineFilm asks of Plex. There is deliberately no method that changes anything on the Plex server: the import
 * only reads.
 */
public interface PlexApi {

    /** True when both the Plex address and token are set. */
    boolean configured();

    /** True when Plex answers a cheap request (one attempt, no retries). */
    boolean reachable();

    /**
     * The ids of the movie libraries.
     *
     * @throws PlexException when Plex cannot answer
     */
    List<String> movieSections();

    /**
     * Every film of a library section, paged until Plex's {@code totalSize} is reached.
     *
     * @param sectionId the section id from {@link #movieSections()}
     * @throws PlexException when Plex cannot answer
     */
    List<PlexFilm> films(String sectionId);

    /**
     * The titles of a library section's collections.
     *
     * @param sectionId the section id from {@link #movieSections()}
     * @throws PlexException when Plex cannot answer
     */
    List<String> collections(String sectionId);

    /**
     * An image of a film, resized by Plex.
     *
     * @param path a validated {@code /library/metadata/<n>/(thumb|art)/<n>} path
     * @param width the width to ask for
     * @param height the height to ask for
     * @throws PlexException when Plex cannot answer or the image is too big
     */
    PlexImage image(String path, int width, int height);
}
