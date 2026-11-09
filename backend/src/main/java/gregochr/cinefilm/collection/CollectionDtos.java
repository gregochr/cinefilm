package gregochr.cinefilm.collection;

import com.fasterxml.jackson.annotation.JsonInclude;
import gregochr.cinefilm.film.FilmDtos.FilmCard;
import java.time.Instant;
import java.util.List;

/** JSON shapes of the collection endpoints. */
public final class CollectionDtos {

    private CollectionDtos() {
    }

    /**
     * One slot of a collection page: either an owned film ({@code owned: true, film}) or a missing part
     * ({@code owned: false, title, year, posterPath}).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PartDto(boolean owned, FilmCard film, String title, Integer year, String posterPath) {
    }

    /** The collection page. */
    public record CollectionPage(Long id, String name, String posterPath, List<PartDto> parts) {
    }

    /**
     * A card of the Library's Collections tab: up to three owned films' posters for the fan (earliest release
     * first), the "2 of 4" counts, and when the newest owned film was added.
     */
    public record CollectionCard(Long id, String name, String posterPath, int ownedCount, int totalCount,
                                 List<String> posterPaths, Instant lastAddedAt) {
    }

    /** The Collections tab: every collection, and how many films the library holds (the Films tab's count). */
    public record CollectionList(int filmCount, List<CollectionCard> collections) {
    }
}
