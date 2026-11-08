package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.credit;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.person;
import static gregochr.cinefilm.TestData.state;
import static gregochr.cinefilm.TestData.view;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.person.Person;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FilmViewTest {

    private final Film blade = film(1L, "Blade Runner", 1982, NOW.minus(Duration.ofDays(400)), "Sci-fi");
    private final Edition finalCut = edition(blade, 10L, "The Final Cut", 117 * MIN, null);
    private final Edition theatrical = edition(blade, 11L, "Theatrical Cut", 117 * MIN, null);

    @Test
    void watchedIsPerEditionAndAFilmIsWatchedIfAnyEditionIs() {
        var view = view(blade, state(finalCut, 117 * MIN, true, NOW, NOW));
        assertThat(view.state(finalCut).orElseThrow().isWatched()).isTrue();
        assertThat(view.state(theatrical)).isEmpty();
        assertThat(view.watched()).isTrue();
        assertThat(view(blade).watched()).isFalse();
        assertThat(view(blade, state(finalCut, 5 * MIN, false, NOW, null)).watched()).isFalse();
    }

    @Test
    void progressComesFromTheMostRecentlyPlayedEdition() {
        Instant earlier = NOW.minus(Duration.ofDays(3));
        var view = view(blade, state(finalCut, 30 * MIN, false, earlier, null),
            state(theatrical, 10 * MIN, false, NOW, null));
        assertThat(view.lastPlayedEdition()).contains(theatrical);
        assertThat(view.lastPlayedAt()).isEqualTo(NOW);
        assertThat(view.progress().orElseThrow().editionId()).isEqualTo(11L);
        assertThat(view.progress().orElseThrow().positionMs()).isEqualTo(10 * MIN);
        assertThat(view.progress().orElseThrow().runtimeMs()).isEqualTo(117 * MIN);
    }

    @Test
    void noProgressWhenTheLastPlayedEditionIsWatchedEvenIfAnotherIsPartWay() {
        Instant earlier = NOW.minus(Duration.ofDays(3));
        var view = view(blade, state(finalCut, 30 * MIN, false, earlier, null),
            state(theatrical, 117 * MIN, true, NOW, NOW));
        assertThat(view.progress()).isEmpty();
    }

    @Test
    void noProgressAtPositionZeroOrNeverPlayed() {
        assertThat(view(blade).progress()).isEmpty();
        assertThat(view(blade).lastPlayedAt()).isNull();
        assertThat(view(blade, state(finalCut, 0, false, NOW, null)).progress()).isEmpty();
    }

    @Test
    void lastActivityFallsBackToWatchedAtWhenNeverPlayed() {
        Instant imported = NOW.minus(Duration.ofDays(3000));
        assertThat(view(blade, state(finalCut, 0, true, null, imported)).lastActivityAt()).isEqualTo(imported);
        assertThat(view(blade).lastActivityAt()).isNull();
        assertThat(view(blade, state(finalCut, 0, true, NOW, imported)).lastActivityAt()).isEqualTo(NOW);
    }

    @Test
    void directorsAndCastAreSeparated() {
        Person mann = person(1L, "Michael Mann");
        Person deNiro = person(2L, "Robert De Niro");
        credit(blade, deNiro, CreditRole.CAST, "Neil McCauley", 1);
        credit(blade, mann, CreditRole.DIRECTOR, null, 0);
        var view = view(blade);
        assertThat(view.directors()).containsExactly(mann);
        assertThat(view.directorNames()).isEqualTo("Michael Mann");
        assertThat(view.cast()).hasSize(1);
    }
}
