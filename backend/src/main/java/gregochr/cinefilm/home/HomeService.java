package gregochr.cinefilm.home;

import gregochr.cinefilm.collection.CollectionService;
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

    private final LibraryLoader loader;
    private final CollectionService collections;
    private final Clock clock;

    public HomeService(LibraryLoader loader, CollectionService collections, Clock clock) {
        this.loader = loader;
        this.collections = collections;
        this.clock = clock;
    }

    /** Builds the hero, the rows, the weekly theme and the collection cards. */
    @Transactional(readOnly = true)
    public Home home() {
        List<FilmView> library = loader.loadAll();
        List<FilmCard> continueWatching = cards(BrowseRules.continueWatching(library));
        WeeklyTheme theme = WeeklyThemeSelector.choose(LocalDate.now(clock),
            library.stream().map(FilmView::film).toList());
        Map<Long, FilmView> byId = library.stream()
            .collect(Collectors.toMap(v -> v.film().getId(), Function.identity()));
        List<FilmCard> themeFilms = theme.films().stream().map(Film::getId).map(byId::get)
            .map(FilmMapper::card).toList();
        return new Home(
            continueWatching.isEmpty() ? null : continueWatching.get(0),
            continueWatching.isEmpty() ? continueWatching : continueWatching.subList(1, continueWatching.size()),
            cards(BrowseRules.recentlyAdded(library, clock.instant())),
            cards(BrowseRules.notWatchedInYears(library, clock.instant())),
            new ThemeDto(theme.title(), theme.caption(), theme.kind(), theme.value(), themeFilms),
            collections.cards(library));
    }

    private static List<FilmCard> cards(List<FilmView> views) {
        return views.stream().map(FilmMapper::card).toList();
    }
}
