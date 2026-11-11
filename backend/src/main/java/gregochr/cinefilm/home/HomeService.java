package gregochr.cinefilm.home;

import gregochr.cinefilm.film.BrowseRules;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmDtos.FilmCard;
import gregochr.cinefilm.film.FilmMapper;
import gregochr.cinefilm.film.FilmView;
import gregochr.cinefilm.film.LibraryLoader;
import gregochr.cinefilm.film.WeeklyTheme;
import gregochr.cinefilm.film.WeeklyThemeSelector;
import gregochr.cinefilm.home.HomeDtos.Home;
import gregochr.cinefilm.home.HomeDtos.ThemeDto;
import gregochr.cinefilm.settings.ServerSettingsService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Assembles the Home screen. */
@Service
public class HomeService {

    /** The most films Home sends in the Continue watching row after the hero. */
    public static final int CONTINUE_WATCHING_ROW = 12;

    private final LibraryLoader loader;
    private final Clock clock;
    private final ServerSettingsService settings;

    /** Creates the service over the library loader, the application clock and the server settings. */
    public HomeService(LibraryLoader loader, Clock clock, ServerSettingsService settings) {
        this.loader = loader;
        this.clock = clock;
        this.settings = settings;
    }

    /**
     * Builds the hero, the rows, the weekly theme. The hero is the newest film in progress and Continue watching is
     * the next {@link #CONTINUE_WATCHING_ROW} after it (4 are shown, the rest are spares for backfill); the total
     * counts the hero and leaves out films removed from Continue watching.
     */
    @Transactional(readOnly = true)
    public Home home() {
        List<FilmView> library = loader.loadAll();
        long minMs = settings.minWatchMs();
        List<FilmCard> continueWatching = cards(BrowseRules.continueWatching(library, minMs), minMs);
        WeeklyTheme theme = WeeklyThemeSelector.choose(LocalDate.now(clock),
            library.stream().map(FilmView::film).toList());
        Map<Long, FilmView> byId = library.stream()
            .collect(Collectors.toMap(v -> v.film().getId(), Function.identity()));
        List<FilmCard> themeFilms = theme.films().stream().map(Film::getId).map(byId::get)
            .map(v -> FilmMapper.card(v, minMs)).toList();
        return new Home(
            continueWatching.isEmpty() ? null : continueWatching.get(0),
            continueWatching.isEmpty() ? continueWatching
                : continueWatching.subList(1, Math.min(1 + CONTINUE_WATCHING_ROW, continueWatching.size())),
            continueWatching.size(),
            cards(BrowseRules.recentlyAdded(library, clock.instant()), minMs),
            cards(BrowseRules.notWatchedInYears(library, clock.instant(), clock.getZone()), minMs),
            new ThemeDto(theme.title(), theme.caption(), theme.kind(), theme.value(), themeFilms));
    }

    private static List<FilmCard> cards(List<FilmView> views, long minMs) {
        return views.stream().map(v -> FilmMapper.card(v, minMs)).toList();
    }
}
