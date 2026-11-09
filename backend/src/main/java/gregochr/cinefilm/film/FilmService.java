package gregochr.cinefilm.film;

import gregochr.cinefilm.collection.CollectionService;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.FilmDtos.Facets;
import gregochr.cinefilm.film.FilmDtos.FilmCard;
import gregochr.cinefilm.film.FilmDtos.FilmDetail;
import gregochr.cinefilm.film.FilmDtos.FilmList;
import gregochr.cinefilm.playback.PreferencesService;
import gregochr.cinefilm.transcode.KeyframeIndexService;
import gregochr.cinefilm.transcode.Transcoder;
import java.util.List;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Library listing and film detail. */
@Service
public class FilmService {

    private final LibraryLoader loader;
    private final FilmRepository films;
    private final PreferencesService preferences;
    private final Transcoder transcoder;
    private final KeyframeIndexService indexes;

    /** Creates the service over the loader, the repository, the preferences service, the transcoder and the indexes. */
    public FilmService(LibraryLoader loader, FilmRepository films, PreferencesService preferences,
                       Transcoder transcoder, KeyframeIndexService indexes) {
        this.loader = loader;
        this.films = films;
        this.preferences = preferences;
        this.transcoder = transcoder;
        this.indexes = indexes;
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
        boolean inCollection = film.getCollection() != null
            && films.findByCollectionId(film.getCollection().getId()).stream().filter(Film::browsable).count()
                >= CollectionService.MIN_OWNED;
        return FilmMapper.detail(view, preferences.resolve(film), inCollection,
            transcoder.mode(), file -> indexes.cached(file).map(KeyframeIndexService.Index::cuts).orElse(null));
    }

    private static Facets facets(List<FilmView> all) {
        TreeSet<String> genres = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        TreeSet<Integer> decades = new TreeSet<>();
        for (FilmView view : all) {
            genres.addAll(view.film().getGenres());
            if (view.film().getYear() != null) {
                decades.add(view.film().getYear() / 10 * 10);
            }
        }
        return new Facets(List.copyOf(genres), List.copyOf(decades));
    }
}
