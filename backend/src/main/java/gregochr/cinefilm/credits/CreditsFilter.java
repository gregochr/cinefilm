package gregochr.cinefilm.credits;

import gregochr.cinefilm.film.CreditsMarkerSource;
import java.util.Locale;

/** Which editions the Admin End credits list shows, by where their marker came from. */
public enum CreditsFilter {
    /** Editions with no source, whether or not a marker is set (the screen's "Needs a look"). */
    NONE,
    /** Editions whose marker came from Plex. */
    PLEX,
    /** Editions whose marker the owner set. */
    OWNER,
    /** Every visible edition. */
    ALL;

    /**
     * Reads the query parameter's value.
     *
     * @param value the text after {@code filter=}
     * @return the filter, or null when the text is none of {@code none}, {@code plex}, {@code owner}, {@code all}
     */
    public static CreditsFilter parse(String value) {
        for (CreditsFilter filter : values()) {
            if (filter.name().toLowerCase(Locale.ROOT).equals(value)) {
                return filter;
            }
        }
        return null;
    }

    /** True when every source is accepted. */
    boolean anySource() {
        return this == ALL;
    }

    /** True when only editions with no source are accepted. */
    boolean unsettled() {
        return this == NONE;
    }

    /** The source a filter accepts; a placeholder (never compared) for {@code ALL} and {@code NONE}. */
    CreditsMarkerSource source() {
        return this == OWNER ? CreditsMarkerSource.OWNER : CreditsMarkerSource.PLEX;
    }
}
