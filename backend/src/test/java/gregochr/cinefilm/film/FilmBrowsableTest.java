package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** When a film is shown in browse: while at least one edition has a file that is there and can be played. */
class FilmBrowsableTest {

    private final Film film = film(1L, "Heat", 1995, NOW);

    @Test
    void aFilmWithAPresentPlayableOriginalIsBrowsable() {
        file(edition(film, 10L, "Original", 100 * MIN, null), "heat.m4v");

        assertThat(film.browsable()).isTrue();
    }

    @Test
    void aFilmWithNoEditionsOrNoFileRowsIsLeftAloneForSeedAndLegacyData() {
        assertThat(film.browsable()).isTrue();
        edition(film, 10L, "Original", 100 * MIN, null);
        assertThat(film.browsable()).isTrue();
    }

    @Test
    void aMissingFileHidesTheFilmUntilAnotherEditionIsPresent() {
        MediaFile gone = file(edition(film, 10L, "Original", 100 * MIN, null), "heat.m4v");
        gone.setPresence(Presence.MISSING);
        assertThat(film.browsable()).isFalse();

        file(edition(film, 11L, "Second", 100 * MIN, null), "heat2.m4v");
        assertThat(film.browsable()).isTrue();
    }

    @Test
    void everyNonPlayableStateHidesTheEdition() {
        for (Playability state : new Playability[] {Playability.PROTECTED, Playability.NEEDS_CONVERSION,
            Playability.UNREADABLE}) {
            Film other = film(2L, "Other", 2000, NOW);
            Edition edition = edition(other, 20L, "Original", 100 * MIN, null);
            file(edition, "x.m4v").setPlayability(state);

            assertThat(edition.hiddenFromBrowse()).as(state.name()).isTrue();
            assertThat(other.browsable()).as(state.name()).isFalse();
        }
    }

    @Test
    void anEditionWithOnlyPremadeRenditionsIsNotHiddenByTheOriginalRules() {
        Edition edition = edition(film, 10L, "Original", 100 * MIN, null);
        MediaFile premade = file(edition, "heat-720.mp4");
        premade.setKind(MediaKind.PREMADE_720);
        premade.setPresence(Presence.MISSING);

        assertThat(edition.hiddenFromBrowse()).isFalse();
    }
}
