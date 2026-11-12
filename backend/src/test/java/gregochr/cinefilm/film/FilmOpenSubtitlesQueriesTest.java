package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.FilmRepository.ImdbRef;
import gregochr.cinefilm.film.FilmRepository.NeedRow;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The queries behind the subtitle search: which films are due, the targeted save and the reset. */
class FilmOpenSubtitlesQueriesTest extends ApiTestBase {

    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(30));

    @Autowired
    private EntityManager entityManager;

    private Film unsearched(String title, String imdbId) {
        Film film = film(null, title, 2000, NOW);
        film.setImdbId(imdbId);
        return film;
    }

    /** A film with an IMDb id and a stored search of {@code searchedFor}. */
    private Long searched(String title, String imdbId, String searchedFor, Integer count, Instant at) {
        Film film = unsearched(title, imdbId);
        film.setOsSearchedImdbId(searchedFor);
        film.setOsForcedCount(count);
        film.setOsSearchedAt(at);
        film.setOsCandidates("[]");
        return films.saveAndFlush(film).getId();
    }

    private List<Long> dueIds() {
        entityManager.clear();
        return films.findOpenSubtitlesDue(CUTOFF).stream().map(ImdbRef::id).toList();
    }

    @Test
    void aFilmNeverSearchedIsDueAndCarriesItsImdbId() {
        Film never = films.saveAndFlush(unsearched("Never", "tt0936501"));

        assertThat(films.findOpenSubtitlesDue(CUTOFF)).containsExactly(new ImdbRef(never.getId(), "tt0936501"));
    }

    @Test
    void aFilmWithNoImdbIdIsNeverDue() {
        films.saveAndFlush(unsearched("No id", null));

        assertThat(films.findOpenSubtitlesDue(CUTOFF)).isEmpty();
    }

    @Test
    void aFilmWithResultsIsNotDueHoweverOldTheSearchIs() {
        searched("Recent", "tt0000001", "tt0000001", 5, NOW);
        searched("Old", "tt0000002", "tt0000002", 5, NOW.minus(Duration.ofDays(900)));

        assertThat(dueIds()).isEmpty();
    }

    @Test
    void aFilmWithNoResultIsDueOnlyOnceTheSearchIsOlderThanThirtyDays() {
        Long below = searched("A below", "tt0000001", "tt0000001", 0, CUTOFF.minusMillis(1));
        searched("B exactly", "tt0000002", "tt0000002", 0, CUTOFF);
        searched("C above", "tt0000003", "tt0000003", 0, CUTOFF.plusMillis(1));
        searched("D recent", "tt0000004", "tt0000004", 0, NOW);

        assertThat(dueIds()).containsExactly(below);
    }

    @Test
    void aSearchWithAnIncompleteMarkerOrForAnotherImdbIdIsDueAgain() {
        Long other = searched("A other", "tt0000001", "tt0000009", 7, NOW);
        Long noMarker = searched("B no marker", "tt0000002", null, 7, NOW);
        Long countNoMarker = searched("C zero no marker", "tt0000003", null, 0, NOW);
        searched("D current", "tt0000004", "tt0000004", 7, NOW);

        assertThat(dueIds()).containsExactlyInAnyOrder(other, noMarker, countNoMarker);
    }

    @Test
    void aMarkerWithNoTimestampIsDue() {
        Long id = searched("Stamp missing", "tt0000001", "tt0000001", 7, null);

        assertThat(dueIds()).containsExactly(id);
    }

    @Test
    void neverSearchedFilmsComeFirstThenTheOldestSearch() {
        Long newestStale = searched("A newest", "tt0000001", "tt0000009", 7, NOW.minus(Duration.ofDays(1)));
        Long oldest = searched("B oldest", "tt0000002", "tt0000009", 7, NOW.minus(Duration.ofDays(400)));
        Long neverLater = films.saveAndFlush(unsearched("C never later", "tt0000003")).getId();
        Long neverFirst = films.saveAndFlush(unsearched("D never first", "tt0000004")).getId();
        Long middle = searched("E middle", "tt0000005", "tt0000009", 7, NOW.minus(Duration.ofDays(40)));

        List<Long> due = dueIds();

        assertThat(due).containsExactly(neverLater, neverFirst, oldest, middle, newestStale);
    }

    @Test
    void savingWritesTheCountTheBestResultTheImdbIdAndTheStampForTheImdbIdSearched() {
        Long id = films.saveAndFlush(unsearched("Taken", "tt0936501")).getId();

        int changed = films.saveOpenSubtitles(id, "tt0936501", 7, "{\"id\":1,\"fileCount\":1}", "[]", NOW);
        entityManager.clear();

        assertThat(changed).isEqualTo(1);
        Film reloaded = films.findById(id).orElseThrow();
        assertThat(reloaded.getOsForcedCount()).isEqualTo(7);
        assertThat(reloaded.getOsBest()).isEqualTo("{\"id\":1,\"fileCount\":1}");
        assertThat(reloaded.getOsSearchedImdbId()).isEqualTo("tt0936501");
        assertThat(reloaded.getOsSearchedAt()).isEqualTo(NOW);
        assertThat(films.findOpenSubtitlesDue(CUTOFF)).isEmpty();
    }

    @Test
    void aSearchWithNoResultIsSavedAsZeroAndNoBest() {
        Long id = films.saveAndFlush(unsearched("Taken", "tt0936501")).getId();

        films.saveOpenSubtitles(id, "tt0936501", 0, null, "[]", NOW);
        entityManager.clear();

        Film reloaded = films.findById(id).orElseThrow();
        assertThat(reloaded.getOsForcedCount()).isZero();
        assertThat(reloaded.getOsBest()).isNull();
        assertThat(reloaded.getOsSearchedAt()).isEqualTo(NOW);
    }

    @Test
    void aFilmWhoseImdbIdChangedMeanwhileKeepsWhatTheChangeWroteAndIsStillDue() {
        Long id = films.saveAndFlush(unsearched("Taken", "tt0936501")).getId();
        // A scan, Fix match or Plex import gave it another IMDb id after the search began.
        Film changed = films.findById(id).orElseThrow();
        changed.setImdbId("tt0113277");
        films.saveAndFlush(changed);

        int rows = films.saveOpenSubtitles(id, "tt0936501", 7, "{\"id\":1,\"fileCount\":1}", "[]", NOW);
        entityManager.clear();

        assertThat(rows).isZero();
        Film reloaded = films.findById(id).orElseThrow();
        assertThat(reloaded.getImdbId()).isEqualTo("tt0113277");
        assertThat(reloaded.getOsForcedCount()).isNull();
        assertThat(reloaded.getOsSearchedAt()).isNull();
        assertThat(reloaded.getOsSearchedImdbId()).isNull();
        assertThat(reloaded.getOsBest()).isNull();
        assertThat(films.findOpenSubtitlesDue(CUTOFF)).containsExactly(new ImdbRef(id, "tt0113277"));
    }

    @Test
    void aFilmThatHasGoneChangesNoRow() {
        assertThat(films.saveOpenSubtitles(987_654L, "tt0936501", 7, null, "[]", NOW)).isZero();
    }

    @Test
    void clearingRemovesWhatASearchSavedMeanwhileEvenThoughTheEntityLoadedNulls() {
        Long id = films.saveAndFlush(unsearched("Taken", "tt0936501")).getId();
        entityManager.clear();
        Film loaded = films.findById(id).orElseThrow();
        // A search commits the old IMDb id's result after the entity was loaded: the entity still holds nulls, so
        // setting them to null again changes nothing for Hibernate; only the explicit statement removes the row's.
        assertThat(films.saveOpenSubtitles(id, "tt0936501", 7, "{\"id\":1}", "[]", NOW)).isEqualTo(1);
        loaded.setImdbId("tt0113277");

        int rows = films.clearOpenSubtitles(id);
        entityManager.clear();

        assertThat(rows).isEqualTo(1);
        Film reloaded = films.findById(id).orElseThrow();
        assertThat(reloaded.getImdbId()).isEqualTo("tt0113277");
        assertThat(reloaded.getOsForcedCount()).isNull();
        assertThat(reloaded.getOsSearchedAt()).isNull();
        assertThat(reloaded.getOsSearchedImdbId()).isNull();
        assertThat(reloaded.getOsBest()).isNull();
    }

    @Test
    void clearingAFilmThatHasGoneChangesNoRow() {
        assertThat(films.clearOpenSubtitles(987_654L)).isZero();
    }

    @Test
    void theNeedRowsCarryTheImdbIdAndTheSearch() {
        Long id = searched("Taken", "tt0936501", "tt0936501", 7, NOW);
        Long never = films.saveAndFlush(unsearched("Zulu", null)).getId();
        entityManager.clear();

        List<NeedRow> rows = films.findNeedRows();

        assertThat(rows).extracting(NeedRow::id).containsExactly(id, never);
        assertThat(rows.get(0).imdbId()).isEqualTo("tt0936501");
        assertThat(rows.get(0).osForcedCount()).isEqualTo(7);
        assertThat(rows.get(0).osSearchedAt()).isEqualTo(NOW);
        assertThat(rows.get(0).osSearchedImdbId()).isEqualTo("tt0936501");
        assertThat(rows.get(1).imdbId()).isNull();
        assertThat(rows.get(1).osForcedCount()).isNull();
    }

    @Test
    void theLatestSearchTimeIsTheNewestOfAllFilmsOrNullWhenNoneWasSearched() {
        assertThat(films.findLatestOpenSubtitlesSearchedAt()).isNull();
        searched("Older", "tt0000001", "tt0000001", 1, NOW.minus(Duration.ofDays(3)));
        searched("Newer", "tt0000002", "tt0000002", 0, NOW.minus(Duration.ofDays(1)));
        films.saveAndFlush(unsearched("Never", "tt0000003"));

        assertThat(films.findLatestOpenSubtitlesSearchedAt()).isEqualTo(NOW.minus(Duration.ofDays(1)));
    }

    @Test
    void aBestResultStoredBeforeTheFileCountWasKeptMakesTheFilmDueAgainAndFirst() {
        Long old = searched("Old best", "tt0000021", "tt0000021", 3, NOW);
        Long current = searched("Current best", "tt0000022", "tt0000022", 3, NOW);
        Long unsearchedYet = films.saveAndFlush(unsearched("Never", "tt0000023")).getId();
        Film oldFilm = films.findById(old).orElseThrow();
        oldFilm.setOsBest("{\"id\":1,\"fileId\":2,\"release\":null}");
        Film currentFilm = films.findById(current).orElseThrow();
        currentFilm.setOsBest("{\"id\":1,\"fileId\":2,\"release\":null,\"fileCount\":1}");
        films.flush();

        assertThat(dueIds()).containsExactly(old, unsearchedYet);
    }

    @Test
    void aFilmSearchedBeforeTheCandidatesWereKeptIsDueAgainWithOneThatHasAResultFirst() {
        Long noResultLegacy = searched("A no result", "tt0000031", "tt0000031", 0, NOW);
        Long bestLegacy = searched("B best", "tt0000032", "tt0000032", 3, NOW);
        Long current = searched("C current", "tt0000033", "tt0000033", 3, NOW);
        Long unsearchedYet = films.saveAndFlush(unsearched("Never", "tt0000034")).getId();
        films.findById(noResultLegacy).orElseThrow().setOsCandidates(null);
        Film best = films.findById(bestLegacy).orElseThrow();
        best.setOsCandidates(null);
        best.setOsBest("{\"id\":1,\"fileId\":2,\"release\":null,\"fileCount\":1}");
        films.findById(current).orElseThrow().setOsBest("{\"id\":1,\"fileId\":2,\"fileCount\":1}");
        films.flush();

        assertThat(dueIds()).containsExactly(bestLegacy, unsearchedYet, noResultLegacy);
    }

    @Test
    void savingWritesTheCandidatesAndAnImdbIdChangeForgetsThem() {
        Long id = films.saveAndFlush(unsearched("Taken", "tt0936501")).getId();

        films.saveOpenSubtitles(id, "tt0936501", 1, "{\"id\":1,\"fileId\":2}", "[{\"id\":1,\"fileId\":2}]", NOW);
        entityManager.clear();
        assertThat(films.findById(id).orElseThrow().getOsCandidates()).isEqualTo("[{\"id\":1,\"fileId\":2}]");

        Film loaded = films.findById(id).orElseThrow();
        loaded.setImdbId("tt0113277");
        assertThat(loaded.getOsCandidates()).isNull();
        films.saveAndFlush(loaded);
        films.saveOpenSubtitles(id, "tt0113277", 1, "{\"id\":1,\"fileId\":2}", "[]", NOW);
        films.clearOpenSubtitles(id);
        entityManager.clear();
        assertThat(films.findById(id).orElseThrow().getOsCandidates()).isNull();
    }
}
