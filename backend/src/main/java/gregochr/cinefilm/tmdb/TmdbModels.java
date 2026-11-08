package gregochr.cinefilm.tmdb;

import java.util.List;

/** The parts of TMDB's responses CineFilm uses. TMDB text is untrusted: it is only ever stored and shown as text. */
public final class TmdbModels {

    private TmdbModels() {
    }

    /**
     * One result of {@code /search/movie}.
     *
     * @param id TMDB id
     * @param title the localised title
     * @param originalTitle the original-language title
     * @param year release year, or null
     * @param popularity TMDB popularity
     * @param posterPath poster path, or null
     */
    public record SearchHit(long id, String title, String originalTitle, Integer year, double popularity,
                            String posterPath) {
    }

    /**
     * A credited person.
     *
     * @param tmdbId TMDB person id
     * @param name the person's name
     * @param profilePath profile image path, or null
     * @param character the character played (cast only), or null
     */
    public record Credit(long tmdbId, String name, String profilePath, String character) {
    }

    /**
     * A TMDB collection reference on a film.
     *
     * @param id TMDB collection id
     * @param name collection name
     * @param posterPath poster path, or null
     */
    public record CollectionRef(long id, String name, String posterPath) {
    }

    /**
     * A film from {@code /movie/{id}?append_to_response=credits}.
     *
     * @param id TMDB id
     * @param title the title
     * @param originalTitle the original-language title
     * @param year release year, or null
     * @param overview the synopsis, or null
     * @param runtimeMinutes runtime in minutes, or null when TMDB has none
     * @param genres genre names
     * @param posterPath poster path, or null
     * @param backdropPath backdrop path, or null
     * @param popularity TMDB popularity
     * @param directors directors in credit order
     * @param cast the top ten cast in billing order
     * @param collection the collection it belongs to, or null
     */
    public record MovieDetails(long id, String title, String originalTitle, Integer year, String overview,
                               Integer runtimeMinutes, List<String> genres, String posterPath, String backdropPath,
                               double popularity, List<Credit> directors, List<Credit> cast,
                               CollectionRef collection) {
    }

    /**
     * One film of a collection.
     *
     * @param id TMDB id
     * @param title the title
     * @param year release year, or null
     * @param posterPath poster path, or null
     */
    public record Part(long id, String title, Integer year, String posterPath) {
    }

    /**
     * A collection from {@code /collection/{id}}, parts in release order.
     *
     * @param id TMDB collection id
     * @param name collection name
     * @param posterPath poster path, or null
     * @param parts the films, oldest first
     */
    public record CollectionDetails(long id, String name, String posterPath, List<Part> parts) {
    }
}
