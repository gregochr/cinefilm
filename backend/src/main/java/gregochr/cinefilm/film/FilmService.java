package gregochr.cinefilm.film;

import gregochr.cinefilm.collection.FilmCollection;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.FilmDtos.CollectionFacet;
import gregochr.cinefilm.film.FilmDtos.Facets;
import gregochr.cinefilm.film.FilmDtos.FilmCard;
import gregochr.cinefilm.film.FilmDtos.FilmDetail;
import gregochr.cinefilm.film.FilmDtos.FilmList;
import gregochr.cinefilm.playback.PreferencesService;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Library listing and film detail. */
@Service
public class FilmService {

    private final LibraryLoader loader;
    private final FilmRepository films;
    private final PreferencesService preferences;

    /** Creates the service over the loader, the film repository and the preferences service. */
    public FilmService(LibraryLoader loader, FilmRepository films, PreferencesService preferences) {
        this.loader = loader;
        this.films = films;
        this.preferences = preferences;
    }

    /** Searches, filters and sorts the library and computes the filter facets over the whole library. */
    @Transactional(readOnly = true)
    public FilmList list(LibraryQuery query) {
        List<FilmView> all = loader.loadAll();
        List<FilmCard> cards = BrowseRules.filterAndSort(all, query).stream().map(FilmMapper::card).toList();
        return new FilmList(cards.size(), cards, facets(all));
    }

    /** The detail page of one film. */
    @Transactional(readOnly = true)
    public FilmDetail detail(Long id) {
        Film film = films.findById(id).filter(Film::browsable)
            .orElseThrow(() -> new NotFoundException("No such film"));
        FilmView view = loader.views(List.of(film)).get(0);
        return FilmMapper.detail(view, preferences.resolve(film));
    }

    private static Facets facets(List<FilmView> all) {
        TreeSet<String> genres = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        TreeSet<Integer> decades = new TreeSet<>();
        Map<Long, String> collections = new TreeMap<>();
        for (FilmView view : all) {
            genres.addAll(view.film().getGenres());
            if (view.film().getYear() != null) {
                decades.add(view.film().getYear() / 10 * 10);
            }
            FilmCollection collection = view.film().getCollection();
            if (collection != null) {
                collections.put(collection.getId(), collection.getName());
            }
        }
        List<CollectionFacet> collectionFacets = collections.entrySet().stream()
            .map(e -> new CollectionFacet(e.getKey(), e.getValue()))
            .sorted(Comparator.comparing(CollectionFacet::name))
            .toList();
        return new Facets(List.copyOf(genres), List.copyOf(decades), collectionFacets);
    }
}
