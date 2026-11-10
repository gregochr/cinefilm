package gregochr.cinefilm.film;

/** Parsed query parameters of {@code GET /api/films}. Null fields mean "no filter". */
public record LibraryQuery(String q, String sort, String genre, Integer decade, String show) {

    /** A query that matches everything, newest first. */
    public static LibraryQuery all() {
        return new LibraryQuery(null, null, null, null, null);
    }
}
