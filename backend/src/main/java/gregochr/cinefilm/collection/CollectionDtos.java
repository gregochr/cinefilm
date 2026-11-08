package gregochr.cinefilm.collection;

import com.fasterxml.jackson.annotation.JsonInclude;
import gregochr.cinefilm.film.FilmDtos.FilmCard;
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

    /** A Home collection card: up to three owned films for the fanned posters, and the "2 of 4" counts. */
    public record CollectionCard(Long id, String name, String posterPath, int ownedCount, int totalCount,
                                 List<String> posterPaths) {
    }
}
