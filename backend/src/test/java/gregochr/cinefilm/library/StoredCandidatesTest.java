package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.TestData;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.tmdb.MatchOutcome.Candidate;
import org.junit.jupiter.api.Test;

/** {@link StoredCandidates}: reading the scan's stored candidate JSON. */
class StoredCandidatesTest {

    private static Film withCandidates(String json) {
        Film film = TestData.film(1L, "Enchanted", 2007, NOW);
        film.setMatchCandidates(json);
        return film;
    }

    @Test
    void storedCandidatesAreReadBestFirst() {
        Film film = withCandidates("[{\"tmdbId\":5,\"title\":\"A\",\"year\":2007,\"runtimeMinutes\":107,"
            + "\"posterPath\":\"/a.jpg\",\"score\":70},{\"tmdbId\":6,\"title\":\"B\",\"year\":null,"
            + "\"runtimeMinutes\":null,\"posterPath\":null,\"score\":40}]");

        assertThat(StoredCandidates.of(film)).containsExactly(
            new Candidate(5, "A", 2007, 107, "/a.jpg", 70), new Candidate(6, "B", null, null, null, 40));
    }

    @Test
    void nothingStoredEmptyOrMalformedAllGiveNoCandidates() {
        assertThat(StoredCandidates.of(withCandidates(null))).isEmpty();
        assertThat(StoredCandidates.of(withCandidates("[]"))).isEmpty();
        assertThat(StoredCandidates.of(withCandidates("{not json"))).isEmpty();
    }
}
