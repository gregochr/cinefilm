package gregochr.cinefilm.home;

import gregochr.cinefilm.film.FilmDtos.FilmCard;
import java.util.List;

/** JSON shape of the Home endpoint. */
public final class HomeDtos {

    private HomeDtos() {
    }

    /** The weekly theme row. {@code kind} and {@code value} let the client link "See all" to a library filter. */
    public record ThemeDto(String title, String caption, String kind, String value, List<FilmCard> films) {
    }

    /**
     * Everything the Home screen shows. {@code continueWatching} is at most the 12 films after the hero;
     * {@code continueWatchingTotal} is every film in Continue watching, the hero included.
     */
    public record Home(FilmCard hero, List<FilmCard> continueWatching, int continueWatchingTotal,
                       List<FilmCard> recentlyAdded,
                       List<FilmCard> notWatchedInYears, ThemeDto weeklyTheme) {
    }
}
