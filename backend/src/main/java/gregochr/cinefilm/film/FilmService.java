package gregochr.cinefilm.film;

import gregochr.cinefilm.collection.CollectionService;
import gregochr.cinefilm.collection.FilmCollection;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.FilmDtos.Facets;
import gregochr.cinefilm.film.FilmDtos.FilmDetail;
import gregochr.cinefilm.film.FilmDtos.FilmList;
import gregochr.cinefilm.film.FilmDtos.LibraryCard;
import gregochr.cinefilm.playback.PreferencesService;
import gregochr.cinefilm.settings.ServerSettingsService;
import gregochr.cinefilm.subtitle.ForcedSubtitleService;
import gregochr.cinefilm.subtitle.FullNeedService;
import gregochr.cinefilm.subtitle.FullSubtitleService;
import gregochr.cinefilm.transcode.KeyframeIndexService;
import gregochr.cinefilm.transcode.Transcoder;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
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
    private final ServerSettingsService settings;
    private final ForcedSubtitleService forcedSubtitles;
    private final FullNeedService fullNeed;
    private final FullSubtitleService fullSubtitles;

    /**
     * Creates the service over the loader, the repository, the preferences service, the transcoder, the indexes and
     * the server settings, the forced subtitles, the full subtitles and the full-subtitles need.
     */
    public FilmService(LibraryLoader loader, FilmRepository films, PreferencesService preferences,
                       Transcoder transcoder, KeyframeIndexService indexes, ServerSettingsService settings,
                       ForcedSubtitleService forcedSubtitles, FullNeedService fullNeed,
                       FullSubtitleService fullSubtitles) {
        this.settings = settings;
        this.forcedSubtitles = forcedSubtitles;
        this.fullNeed = fullNeed;
        this.fullSubtitles = fullSubtitles;
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
        Set<Long> qualifying = qualifyingCollections(all);
        long minMs = settings.minWatchMs();
        List<LibraryCard> cards = BrowseRules.filterAndSort(all, query, minMs).stream()
            .map(v -> FilmMapper.libraryCard(v, qualifying, minMs)).toList();
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
        Map<Long, FilmDtos.ForcedSubtitleDto> forced = forcedSubtitles.forEditions(film.getEditions());
        Map<Long, FilmDtos.FullSubtitleDto> full = fullSubtitles.forEditions(film.getEditions());
        return FilmMapper.detail(view, preferences.resolve(film), inCollection,
            transcoder.mode(), file -> indexes.cachedFor(file, file.getEdition().partCount() > 1)
                .map(KeyframeIndexService.Index::cuts).orElse(null), edition -> forced.get(edition.getId()),
            fullNeed.needOf(film), edition -> full.get(edition.getId()));
    }

    /** The ids of the collections with at least {@link CollectionService#MIN_OWNED} films in the library. */
    private static Set<Long> qualifyingCollections(List<FilmView> all) {
        return all.stream()
            .map(v -> v.film().getCollection())
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(FilmCollection::getId, Collectors.counting()))
            .entrySet().stream()
            .filter(e -> e.getValue() >= CollectionService.MIN_OWNED)
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
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
