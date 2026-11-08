package gregochr.cinefilm.home;

import gregochr.cinefilm.collection.CollectionDtos.CollectionCard;
import gregochr.cinefilm.film.FilmDtos.FilmCard;
import java.util.List;

/** JSON shape of the Home endpoint. */
public final class HomeDtos {

    private HomeDtos() {
    }

    /** The weekly theme row. {@code kind} and {@code value} let the client link "See all" to a library filter. */
    public record ThemeDto(String title, String caption, String kind, String value, List<FilmCard> films) {
    }

    /** Everything the Home screen shows. */
    public record Home(FilmCard hero, List<FilmCard> continueWatching, List<FilmCard> recentlyAdded,
                       List<FilmCard> notWatchedInYears, ThemeDto weeklyTheme, List<CollectionCard> collections) {
    }
}
