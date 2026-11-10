package gregochr.cinefilm.search;

import gregochr.cinefilm.collection.CollectionDtos.CollectionCard;
import gregochr.cinefilm.collection.CollectionService;
import gregochr.cinefilm.film.Credit;
import gregochr.cinefilm.film.CreditRole;
import gregochr.cinefilm.film.FilmView;
import gregochr.cinefilm.film.LibraryLoader;
import gregochr.cinefilm.person.Person;
import gregochr.cinefilm.search.SearchIndexDtos.CollectionEntry;
import gregochr.cinefilm.search.SearchIndexDtos.FilmEntry;
import gregochr.cinefilm.search.SearchIndexDtos.PersonEntry;
import gregochr.cinefilm.search.SearchIndexDtos.SearchIndex;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds the compact search index from the browsable library. Lists are in id order so the output is stable. */
@Service
public class SearchIndexService {

    private static final int POSTERS = 2;

    private final LibraryLoader loader;
    private final CollectionService collections;

    /** Creates the service over the library loader and the collection service (whose cards it reuses). */
    public SearchIndexService(LibraryLoader loader, CollectionService collections) {
        this.loader = loader;
        this.collections = collections;
    }

    /** Builds the index from the library as it is now. */
    @Transactional(readOnly = true)
    public SearchIndex build() {
        List<FilmView> library = loader.loadAll();
        List<FilmEntry> films = library.stream()
            .sorted(Comparator.comparing(v -> v.film().getId()))
            .map(SearchIndexService::film)
            .toList();
        List<CollectionEntry> cards = collections.cards(library).stream()
            .sorted(Comparator.comparing(CollectionCard::id))
            .map(c -> new CollectionEntry(c.id(), c.name(), c.ownedCount(), c.totalCount(),
                c.posterPaths().stream().filter(Objects::nonNull).limit(POSTERS).toList()))
            .toList();
        return new SearchIndex(films, cards, people(library));
    }

    private static FilmEntry film(FilmView view) {
        Long left = view.progress().map(p -> Math.max(0L, p.runtimeMs() - p.positionMs())).orElse(null);
        Instant playedAt = view.lastPlayedAt();
        return new FilmEntry(view.film().getId(), view.film().getTitle(), view.film().getYear(),
            view.directors().stream().map(Person::getName).toList(), view.film().getPosterPath(), left,
            playedAt == null ? null : playedAt.toString(), view.film().getSortTitle());
    }

    /** Anyone with a director or cast credit on a browsable film, with how many such films they have in each role. */
    private static List<PersonEntry> people(List<FilmView> library) {
        Map<Long, Person> byId = new HashMap<>();
        Map<Long, Integer> acted = new HashMap<>();
        Map<Long, Integer> directed = new HashMap<>();
        for (FilmView view : library) {
            Set<Long> actors = new HashSet<>();
            Set<Long> directors = new HashSet<>();
            for (Credit credit : view.film().getCredits()) {
                Person person = credit.getPerson();
                if (credit.getRole() == CreditRole.CAST) {
                    actors.add(person.getId());
                } else {
                    directors.add(person.getId());
                }
                byId.putIfAbsent(person.getId(), person);
            }
            actors.forEach(id -> acted.merge(id, 1, Integer::sum));
            directors.forEach(id -> directed.merge(id, 1, Integer::sum));
        }
        return byId.values().stream()
            .sorted(Comparator.comparing(Person::getId))
            .map(p -> new PersonEntry(p.getId(), p.getName(), acted.getOrDefault(p.getId(), 0),
                directed.getOrDefault(p.getId(), 0)))
            .toList();
    }
}
