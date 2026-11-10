package gregochr.cinefilm.film;

import java.util.List;

/** The Home weekly theme: what it is, how it is titled, and which films it shows. */
public record WeeklyTheme(String kind, String value, String title, String caption, List<Film> films) {

    /** Caption shown under every theme. */
    public static final String STANDING_CAPTION = "A new theme every Monday";
}
