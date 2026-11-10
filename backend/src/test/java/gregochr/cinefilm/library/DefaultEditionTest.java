package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.TestData;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.Presence;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The edition a film opens on until the owner chooses. */
class DefaultEditionTest {

    private final Film film = TestData.film(1L, "Gladiator", 2000, NOW);

    private Edition edition(long id, String name, long runtimeMin) {
        Edition edition = TestData.edition(film, id, name, runtimeMin * MIN, null);
        TestData.file(edition, "Gladiator " + id + ".m4v");
        return edition;
    }

    @Test
    void anEditionNamedTheatricalWinsOverALongerOne() {
        Edition extended = edition(1, "Extended Cut", 175);
        Edition theatrical = edition(2, "Theatrical Cut", 155);

        assertThat(DefaultEdition.choose(List.of(extended, theatrical))).contains(theatrical);
    }

    @Test
    void anUntaggedEditionWinsOverALongerTaggedOne() {
        Edition tagged = edition(1, "Extended Cut", 175);
        Edition plain = edition(2, "Original", 155);

        assertThat(DefaultEdition.choose(List.of(tagged, plain))).contains(plain);
    }

    @Test
    void withNeitherTheLongestWins() {
        Edition shorter = edition(1, "Final Cut", 110);
        Edition longer = edition(2, "Extended Cut", 130);

        assertThat(DefaultEdition.choose(List.of(shorter, longer))).contains(longer);
    }

    @Test
    void equalRuntimesKeepTheFirst() {
        Edition one = edition(1, "Final Cut", 110);
        Edition two = edition(2, "Extended Cut", 110);

        assertThat(DefaultEdition.choose(List.of(one, two))).contains(one);
    }

    @Test
    void anEditionThatCannotBePlayedIsPassedOverWhileAnotherCan() {
        Edition missing = edition(1, "Original", 155);
        missing.getMediaFiles().get(0).setPresence(Presence.MISSING);
        Edition extended = edition(2, "Extended Cut", 175);

        assertThat(DefaultEdition.choose(List.of(missing, extended))).contains(extended);
    }

    @Test
    void whenNoneCanBePlayedTheOrdinaryRulesStillPickOne() {
        Edition missing = edition(1, "Original", 155);
        missing.getMediaFiles().get(0).setPresence(Presence.MISSING);

        assertThat(DefaultEdition.choose(List.of(missing))).contains(missing);
    }

    @Test
    void noEditionsGivesNothing() {
        assertThat(DefaultEdition.choose(List.of())).isEmpty();
    }
}
