package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.TestData;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import org.junit.jupiter.api.Test;

/** The different-film guard: identical comparison titles, or the owner's say-so. */
class GroupingGuardTest {

    private static Film film(String... paths) {
        Film film = TestData.film(null, "A film", 2000, NOW);
        for (String path : paths) {
            Edition edition = TestData.edition(film, null, "Original", 100 * MIN, null);
            TestData.file(edition, path);
        }
        return film;
    }

    @Test
    void sameFilmComparesTitlesNotNames() {
        assertThat(GroupingGuard.sameFilm("Robin Hood.m4v", "Robin Hood - HD.m4v")).isTrue();
        assertThat(GroupingGuard.sameFilm("Alien.m4v", "Alien Director's Cut.m4v")).isTrue();
        assertThat(GroupingGuard.sameFilm("Rocky IV.m4v", "Rocky 4 (1985).m4v")).isTrue();
        assertThat(GroupingGuard.sameFilm("Rocky IV.m4v", "Rocky V.m4v")).isFalse();
        assertThat(GroupingGuard.sameFilm("Karate Kid.m4v", "Karate Kid 2.m4v")).isFalse();
        assertThat(GroupingGuard.sameFilm("Heat.m4v", "Heat copy.m4v")).isFalse();
    }

    @Test
    void aFileMayJoinAFilmWithTheSameTitleAndNotOneWithAnother() {
        Film heat = film("Heat.m4v");

        assertThat(GroupingGuard.accepts(heat, "Heat - Director's Cut.m4v")).isTrue();
        assertThat(GroupingGuard.accepts(heat, "Heat 2.m4v")).isFalse();
    }

    @Test
    void aFilmWithSeveralTitlesAcceptsAnyOfThem() {
        Film mixed = film("Rocky IV.m4v", "Rocky V.m4v");

        assertThat(GroupingGuard.accepts(mixed, "Rocky 5.m4v")).isTrue();
        assertThat(GroupingGuard.accepts(mixed, "Rocky 6.m4v")).isFalse();
    }

    @Test
    void aFilmWithNoFilesYetAcceptsAnything() {
        assertThat(GroupingGuard.accepts(film(), "Anything.m4v")).isTrue();
    }

    @Test
    void aConfirmedFilmIsExempt() {
        Film confirmed = film("Heat.m4v");
        confirmed.setEditionsConfirmed(true);

        assertThat(GroupingGuard.accepts(confirmed, "Heat 2.m4v")).isTrue();
    }

    @Test
    void aFilmMayMergeIntoOneThatHasEveryTitleItHas() {
        Film target = film("Heat.m4v", "Heat - HD.m4v");

        assertThat(GroupingGuard.accepts(target, film("Heat (1995).m4v"))).isTrue();
        assertThat(GroupingGuard.accepts(target, film("Heat 2.m4v"))).isFalse();
        assertThat(GroupingGuard.accepts(target, film("Heat.m4v", "Heat 2.m4v"))).isFalse();
    }

    @Test
    void aFilmWithNoFilesMergesFreely() {
        assertThat(GroupingGuard.accepts(film(), film("Heat.m4v"))).isTrue();
        assertThat(GroupingGuard.accepts(film("Heat.m4v"), film())).isTrue();
    }

    @Test
    void eitherFilmBeingConfirmedExemptsAMerge() {
        Film confirmed = film("Heat.m4v");
        confirmed.setEditionsConfirmed(true);

        assertThat(GroupingGuard.accepts(confirmed, film("Heat 2.m4v"))).isTrue();
        assertThat(GroupingGuard.accepts(film("Heat 2.m4v"), confirmed)).isTrue();
    }
}
