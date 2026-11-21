package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.ApiTestBase;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The one "needs a match" rule: {@link Film#needsMatch()} and the To do queries agree for every state. */
class FilmNeedsMatchTest extends ApiTestBase {

    @Autowired
    private EntityManager entityManager;

    @Test
    void theQueriesAndTheMethodAgreeForEveryStatusWithAndWithoutATmdbId() {
        List<Long> expected = new ArrayList<>();
        for (MatchStatus status : MatchStatus.values()) {
            for (boolean hasId : new boolean[] {false, true}) {
                Film film = film(null, status + (hasId ? " with id" : " no id"), 2000, NOW);
                film.setMatchStatus(status);
                film.setTmdbId(hasId ? 100L + status.ordinal() : null);
                Film saved = films.saveAndFlush(film);
                if (saved.needsMatch()) {
                    expected.add(saved.getId());
                }
            }
        }
        entityManager.clear();

        List<Film> listed = films.findNeedingMatch();

        assertThat(listed).extracting(Film::getId).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(films.countNeedingMatch()).isEqualTo(expected.size());
        assertThat(listed).allMatch(Film::needsMatch);
        assertThat(films.findAll().stream().filter(Film::needsMatch).map(Film::getId))
            .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(expected).as("NEEDS_MATCH both ways, plus MATCHED with no id").hasSize(3);
    }

    @Test
    void pendingAndLeftUnmatchedFilmsWithNoTmdbIdAreNotListed() {
        for (MatchStatus status : List.of(MatchStatus.PENDING, MatchStatus.LEFT_UNMATCHED)) {
            Film film = film(null, status.name(), 2000, NOW);
            film.setMatchStatus(status);
            assertThat(film.needsMatch()).as(status.name()).isFalse();
        }
    }
}
