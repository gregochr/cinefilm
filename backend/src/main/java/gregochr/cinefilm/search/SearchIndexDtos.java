package gregochr.cinefilm.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * JSON shapes of {@code GET /api/search-index}: short keys, nulls left out, so the whole library stays small enough
 * to ship to the browser once and search there.
 */
public final class SearchIndexDtos {

    private SearchIndexDtos() {
    }

    /**
     * A browsable film: {@code t} title, {@code y} year, {@code d} director names in credit order, {@code p} poster
     * path, {@code left} milliseconds remaining when it is part-watched, {@code at} when it was last played (ISO
     * instant), {@code s} the sort title.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FilmEntry(Long id, String t, Integer y, List<String> d, String p, Long left, String at, String s) {
    }

    /** A collection of at least {@code MIN_OWNED} owned films: {@code n} name, {@code o} owned, {@code of} total. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CollectionEntry(Long id, String n, int o, int of, List<String> p) {
    }

    /** A person credited on a browsable film: {@code a} films as an actor, {@code d} films as a director. */
    public record PersonEntry(Long id, String n, int a, int d) {
    }

    /** The whole index. */
    public record SearchIndex(List<FilmEntry> films, List<CollectionEntry> collections, List<PersonEntry> people) {
    }
}
