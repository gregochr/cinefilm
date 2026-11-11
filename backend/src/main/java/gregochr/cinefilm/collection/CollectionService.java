package gregochr.cinefilm.collection;

import gregochr.cinefilm.collection.CollectionDtos.CollectionCard;
import gregochr.cinefilm.collection.CollectionDtos.CollectionList;
import gregochr.cinefilm.collection.CollectionDtos.CollectionPage;
import gregochr.cinefilm.collection.CollectionDtos.PartDto;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.FilmMapper;
import gregochr.cinefilm.film.BrowseRules;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmView;
import gregochr.cinefilm.film.LibraryLoader;
import gregochr.cinefilm.settings.ServerSettingsService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Collection pages and the Library's collection cards. */
@Service
public class CollectionService {

    /** A TMDB collection counts as a collection only when at least this many of its films are in the library. */
    public static final int MIN_OWNED = 2;

    private static final int FAN_SIZE = 3;

    private final FilmCollectionRepository collections;
    private final FilmRepository films;
    private final LibraryLoader loader;
    private final ServerSettingsService settings;

    /** Creates the service over its repositories, the library loader and the server settings. */
    public CollectionService(FilmCollectionRepository collections, FilmRepository films, LibraryLoader loader,
                             ServerSettingsService settings) {
        this.settings = settings;
        this.collections = collections;
        this.films = films;
        this.loader = loader;
    }

    /** One slot of a collection in release order: an owned film, or a missing part. */
    private record Slot(FilmView owned, CollectionPart missing) {
    }

    /** The collection page: owned films in release order, with missing parts in their place. */
    @Transactional(readOnly = true)
    public CollectionPage get(Long id) {
        FilmCollection collection = collections.findById(id)
            .orElseThrow(() -> new NotFoundException("No such collection"));
        List<FilmView> owned = loader.views(films.findByCollectionId(id));
        long minMs = settings.minWatchMs();
        List<PartDto> parts = slots(collection, owned).stream()
            .map(s -> s.owned() != null
                ? new PartDto(true, FilmMapper.card(s.owned(), minMs), null, null, null)
                : new PartDto(false, null, s.missing().getTitle(), s.missing().getYear(),
                    s.missing().getPosterPath()))
            .toList();
        return new CollectionPage(collection.getId(), collection.getName(), collection.getPosterPath(), parts);
    }

    /** The Collections tab: the cards, and the number of films in the library. */
    @Transactional(readOnly = true)
    public CollectionList list() {
        List<FilmView> library = loader.loadAll();
        return new CollectionList(library.size(), cards(library));
    }

    /** Cards for every collection with at least {@link #MIN_OWNED} owned films. Call inside a transaction. */
    public List<CollectionCard> cards(List<FilmView> library) {
        List<CollectionCard> cards = new ArrayList<>();
        for (FilmCollection collection : collections.findAll()) {
            List<FilmView> owned = library.stream()
                .filter(v -> v.film().getCollection() != null
                    && Objects.equals(v.film().getCollection().getId(), collection.getId()))
                .toList();
            if (owned.size() < MIN_OWNED) {
                continue;
            }
            List<Slot> slots = slots(collection, owned);
            cards.add(new CollectionCard(collection.getId(), collection.getName(), collection.getPosterPath(),
                owned.size(), slots.size(),
                slots.stream().filter(s -> s.owned() != null).limit(FAN_SIZE)
                    .map(s -> s.owned().film().getPosterPath()).toList(),
                owned.stream().map(v -> v.film().getAddedAt()).max(Comparator.naturalOrder()).orElse(null)));
        }
        cards.sort(Comparator.comparing(CollectionCard::name));
        return cards;
    }

    private static List<Slot> slots(FilmCollection collection, List<FilmView> owned) {
        List<Slot> slots = new ArrayList<>();
        Set<Long> matched = new HashSet<>();
        List<CollectionPart> parts = collection.getParts().stream()
            .sorted(Comparator.comparingInt(CollectionPart::getReleaseOrder)).toList();
        for (CollectionPart part : parts) {
            FilmView match = owned.stream()
                .filter(v -> v.film().getTmdbId() != null && v.film().getTmdbId().equals(part.getTmdbId()))
                .findFirst().orElse(null);
            if (match == null) {
                slots.add(new Slot(null, part));
            } else {
                matched.add(match.film().getId());
                slots.add(new Slot(match, null));
            }
        }
        owned.stream()
            .filter(v -> !matched.contains(v.film().getId()))
            .sorted(BrowseRules.BY_YEAR.thenComparing(v -> v.film().getSortTitle()))
            .forEach(v -> slots.add(new Slot(v, null)));
        return slots;
    }
}
