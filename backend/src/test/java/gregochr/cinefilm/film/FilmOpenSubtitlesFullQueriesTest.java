package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.FilmRepository.ImdbRef;
import gregochr.cinefilm.film.FilmRepository.OsFullRef;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The queries behind the search for full English subtitles: which films are due, the targeted save and the reset. */
class FilmOpenSubtitlesFullQueriesTest extends ApiTestBase {

    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(30));
    private static final String CANDIDATES = "[{\"id\":1,\"fileId\":2}]";

    @Autowired
    private EntityManager entityManager;

    private Film unsearched(String title, String imdbId) {
        Film film = film(null, title, 2000, NOW);
        film.setImdbId(imdbId);
        return film;
    }

    /** A film with an IMDb id and a stored full search of {@code searchedFor}. */
    private Long searched(String title, String imdbId, String searchedFor, String candidates, Instant at) {
        Film film = unsearched(title, imdbId);
        film.setOsFullSearchedImdbId(searchedFor);
        film.setOsFullCandidates(candidates);
        film.setOsFullSearchedAt(at);
        return films.saveAndFlush(film).getId();
    }

    private List<Long> dueIds() {
        entityManager.clear();
        return films.findOpenSubtitlesFullDue(CUTOFF).stream().map(ImdbRef::id).toList();
    }

    @Test
    void aFilmNeverSearchedForFullSubtitlesIsDueEvenWhenItsForcedSearchIsDone() {
        Film never = unsearched("Never", "tt0936501");
        never.setOsSearchedImdbId("tt0936501");
        never.setOsSearchedAt(NOW);
        never.setOsForcedCount(3);
        never.setOsCandidates("[]");
        films.saveAndFlush(never);

        assertThat(films.findOpenSubtitlesFullDue(CUTOFF)).containsExactly(new ImdbRef(never.getId(), "tt0936501"));
        assertThat(films.findOpenSubtitlesDue(CUTOFF)).isEmpty();
    }

    @Test
    void aFilmWithNoImdbIdIsNeverDue() {
        films.saveAndFlush(unsearched("No id", null));

        assertThat(films.findOpenSubtitlesFullDue(CUTOFF)).isEmpty();
    }

    @Test
    void aFilmWithResultsIsNotDueHoweverOldTheSearchIs() {
        searched("Recent", "tt0000001", "tt0000001", CANDIDATES, NOW);
        searched("Old", "tt0000002", "tt0000002", CANDIDATES, NOW.minus(Duration.ofDays(900)));

        assertThat(dueIds()).isEmpty();
    }

    @Test
    void aFilmWithNoResultIsDueOnlyOnceTheSearchIsOlderThanThirtyDays() {
        Long below = searched("A below", "tt0000001", "tt0000001", "[]", CUTOFF.minusMillis(1));
        searched("B exactly", "tt0000002", "tt0000002", "[]", CUTOFF);
        searched("C above", "tt0000003", "tt0000003", "[]", CUTOFF.plusMillis(1));
        searched("D recent", "tt0000004", "tt0000004", "[]", NOW);

        assertThat(dueIds()).containsExactly(below);
    }

    @Test
    void aSearchForAnotherImdbIdOrWithAMissingMarkerIsDueAgain() {
        Long other = searched("A other", "tt0000001", "tt0000009", CANDIDATES, NOW);
        Long noId = searched("B no id", "tt0000002", null, CANDIDATES, NOW);
        Long noCandidates = searched("C no candidates", "tt0000003", "tt0000003", null, NOW);
        Long noStamp = searched("D no stamp", "tt0000004", "tt0000004", CANDIDATES, null);

        assertThat(dueIds()).containsExactlyInAnyOrder(other, noId, noCandidates, noStamp);
    }

    @Test
    void neverSearchedFilmsComeFirstThenTheOldestSearch() {
        Long newer = searched("A newer", "tt0000001", "tt0000009", CANDIDATES, NOW.minus(Duration.ofDays(1)));
        Long older = searched("B older", "tt0000002", "tt0000009", CANDIDATES, NOW.minus(Duration.ofDays(5)));
        Long never = films.saveAndFlush(unsearched("C never", "tt0000003")).getId();

        assertThat(dueIds()).containsExactly(never, older, newer);
    }

    @Test
    void savingWritesTheCandidatesTheImdbIdAndTheStampAndLeavesTheForcedSearchAlone() {
        Film film = unsearched("Taken", "tt0936501");
        film.setOsCandidates("[{\"id\":9,\"fileId\":9}]");
        film.setOsForcedCount(4);
        Long id = films.saveAndFlush(film).getId();

        int rows = films.saveOpenSubtitlesFull(id, "tt0936501", CANDIDATES, NOW);
        entityManager.clear();

        assertThat(rows).isEqualTo(1);
        Film reloaded = films.findById(id).orElseThrow();
        assertThat(reloaded.getOsFullCandidates()).isEqualTo(CANDIDATES);
        assertThat(reloaded.getOsFullSearchedImdbId()).isEqualTo("tt0936501");
        assertThat(reloaded.getOsFullSearchedAt()).isEqualTo(NOW);
        assertThat(reloaded.getOsCandidates()).isEqualTo("[{\"id\":9,\"fileId\":9}]");
        assertThat(reloaded.getOsForcedCount()).isEqualTo(4);
        assertThat(films.findOpenSubtitlesFull()).containsExactly(new OsFullRef(id, "tt0936501", CANDIDATES));
    }

    @Test
    void aFilmWhoseImdbIdChangedMeanwhileKeepsWhatTheChangeWroteAndIsStillDue() {
        Long id = films.saveAndFlush(unsearched("Taken", "tt0936501")).getId();
        Film changed = films.findById(id).orElseThrow();
        changed.setImdbId("tt0113277");
        films.saveAndFlush(changed);

        int rows = films.saveOpenSubtitlesFull(id, "tt0936501", CANDIDATES, NOW);
        entityManager.clear();

        assertThat(rows).isZero();
        Film reloaded = films.findById(id).orElseThrow();
        assertThat(reloaded.getOsFullCandidates()).isNull();
        assertThat(reloaded.getOsFullSearchedImdbId()).isNull();
        assertThat(reloaded.getOsFullSearchedAt()).isNull();
        assertThat(films.findOpenSubtitlesFullDue(CUTOFF)).containsExactly(new ImdbRef(id, "tt0113277"));
    }

    @Test
    void aFilmThatHasGoneChangesNoRow() {
        assertThat(films.saveOpenSubtitlesFull(987_654L, "tt0936501", CANDIDATES, NOW)).isZero();
    }

    @Test
    void onlyFilmsWithFullCandidatesFromTheirCurrentImdbIdAreListedAndAnEmptySearchIsNot() {
        Long good = searched("Good", "tt0000001", "tt0000001", CANDIDATES, NOW);
        searched("Empty", "tt0000002", "tt0000002", "[]", NOW);
        searched("Stale", "tt0000003", "tt0000009", CANDIDATES, NOW);
        searched("Never", "tt0000004", null, null, null);

        assertThat(films.findOpenSubtitlesFull()).containsExactly(new OsFullRef(good, "tt0000001", CANDIDATES));
    }

    @Test
    void changingTheImdbIdForgetsTheFullSearchInTheEntityAndClearingWipesWhatASearchSavedMeanwhile() {
        Long id = films.saveAndFlush(unsearched("Taken", "tt0936501")).getId();
        entityManager.clear();
        Film loaded = films.findById(id).orElseThrow();
        // A search commits the old IMDb id's result after the entity was loaded: the entity holds nulls, so only the
        // explicit statement removes the row's values.
        assertThat(films.saveOpenSubtitlesFull(id, "tt0936501", CANDIDATES, NOW)).isEqualTo(1);
        loaded.setImdbId("tt0113277");

        int rows = films.clearOpenSubtitles(id);
        entityManager.clear();

        assertThat(rows).isEqualTo(1);
        Film reloaded = films.findById(id).orElseThrow();
        assertThat(reloaded.getImdbId()).isEqualTo("tt0113277");
        assertThat(reloaded.getOsFullCandidates()).isNull();
        assertThat(reloaded.getOsFullSearchedImdbId()).isNull();
        assertThat(reloaded.getOsFullSearchedAt()).isNull();
    }

    @Test
    void theEntityForgetsTheFullSearchWhenTheImdbIdChangesAndKeepsItWhenItDoesNot() {
        Film film = unsearched("Taken", "tt0936501");
        film.setOsFullCandidates(CANDIDATES);
        film.setOsFullSearchedImdbId("tt0936501");
        film.setOsFullSearchedAt(NOW);

        film.setImdbId("tt0936501");
        assertThat(film.getOsFullCandidates()).isEqualTo(CANDIDATES);

        film.setImdbId("tt0113277");
        assertThat(film.getOsFullCandidates()).isNull();
        assertThat(film.getOsFullSearchedImdbId()).isNull();
        assertThat(film.getOsFullSearchedAt()).isNull();
    }
}
