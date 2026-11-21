package gregochr.cinefilm.credits;

import gregochr.cinefilm.credits.CreditsApply.PlexState;
import gregochr.cinefilm.film.CreditsMarkerSource;
import java.util.List;

/** Response bodies of the Admin End credits endpoints. */
public final class CreditsAdminDtos {

    private CreditsAdminDtos() {
    }

    /**
     * How many visible editions each filter shows.
     *
     * @param none editions with no source (a marker with no source yet included)
     * @param plex editions whose marker came from Plex
     * @param owner editions whose marker the owner set
     * @param all every visible edition
     */
    public record Counts(long none, long plex, long owner, long all) {
    }

    /**
     * What Plex says about an edition's last part, computed whatever the edition's own source is.
     *
     * @param state why Plex's row does or does not apply
     * @param markerMs Plex's time in edition milliseconds for {@code REFUSED} and {@code USABLE}; null otherwise
     */
    public record Plex(PlexState state, Long markerMs) {
    }

    /**
     * One edition in the list.
     *
     * @param editionId the edition
     * @param filmId its film
     * @param title the film's title
     * @param year the film's release year; null when unknown
     * @param editionName the edition's name
     * @param editionCount how many visible editions the film has
     * @param runtimeMs the edition's timeline length; null when the parts have no timeline
     * @param markerMs the edition's credits marker; null when none
     * @param source where the marker came from; null when none or not settled yet
     * @param plex Plex's row as it stands
     */
    public record CreditsRow(long editionId, long filmId, String title, Integer year, String editionName,
                             int editionCount, Long runtimeMs, Long markerMs, CreditsMarkerSource source,
                             Plex plex) {
    }

    /**
     * Response of {@code GET /api/admin/credits}.
     *
     * @param counts the four filters' counts
     * @param page the 0-based page
     * @param size the page size used (after the cap)
     * @param total the chosen filter's count
     * @param items the page's rows
     */
    public record CreditsList(Counts counts, int page, int size, long total, List<CreditsRow> items) {
    }
}
