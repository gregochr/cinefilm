package gregochr.cinefilm.library;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.tmdb.MatchOutcome.Candidate;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Reads the match candidates the scan stored on a film (JSON, best first). */
final class StoredCandidates {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private StoredCandidates() {
    }

    /**
     * The film's stored candidates, best first.
     *
     * @param film the film
     * @return the candidates; empty when none are stored or the stored text cannot be read
     */
    static List<Candidate> of(Film film) {
        if (film.getMatchCandidates() == null) {
            return List.of();
        }
        try {
            return JSON.readValue(film.getMatchCandidates(), new TypeReference<>() { });
        } catch (JacksonException e) {
            return List.of();
        }
    }
}
