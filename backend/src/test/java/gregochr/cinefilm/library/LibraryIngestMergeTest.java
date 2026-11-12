package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.TestData;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreference;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.film.ForcedNeedChoice;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The shared merge, called directly (the scan's own merge goes through it too). */
class LibraryIngestMergeTest extends ApiTestBase {

    @Autowired
    private LibraryIngest ingest;

    @Autowired
    private EditionRepository editions;

    @Autowired
    private EntityManager em;

    @MockitoBean
    private TmdbApi tmdb;

    private Film film(String title, String path) {
        Film film = TestData.film(null, title, 1995, NOW);
        TestData.file(TestData.edition(film, null, "Original", 100 * MIN, null), path);
        film.getGenres().add("Crime");
        return films.saveAndFlush(film);
    }

    @Test
    void editionsWithTheirWatchStateAndPreferencesMoveToTheSurvivorAndTheSourceGoes() {
        Film from = film("From", "From.m4v");
        Film to = film("To", "To.m4v");
        Edition moving = from.getEditions().get(0);
        saveState(TestData.state(moving, 12 * MIN, false, NOW, null));
        FilmPreference preference = new FilmPreference(from.getId());
        preference.setLastEditionId(moving.getId());
        preference.setSubtitle("English");
        preferences.save(preference);
        Long movingId = moving.getId();

        ingest.merge(from.getId(), to.getId());

        em.flush();
        em.clear();
        assertThat(films.findById(from.getId())).isEmpty();
        assertThat(films.findById(to.getId()).orElseThrow().getEditions()).hasSize(2);
        assertThat(editions.findById(movingId).orElseThrow().getFilm().getId()).isEqualTo(to.getId());
        assertThat(states.findById(movingId).orElseThrow().getPositionMs()).isEqualTo(12 * MIN);
        FilmPreference moved = preferences.findById(to.getId()).orElseThrow();
        assertThat(moved.getSubtitle()).isEqualTo("English");
        assertThat(moved.getLastEditionId()).isEqualTo(movingId);
        assertThat(preferences.findById(from.getId())).isEmpty();
    }

    @Test
    void theSurvivorsOwnPreferencesWinAndTheMergedOnesAreDropped() {
        Film from = film("From", "From.m4v");
        Film to = film("To", "To.m4v");
        FilmPreference theirs = new FilmPreference(to.getId());
        theirs.setSubtitle("French");
        preferences.save(theirs);
        FilmPreference mine = new FilmPreference(from.getId());
        mine.setSubtitle("English");
        preferences.save(mine);

        ingest.merge(from.getId(), to.getId());

        em.flush();
        em.clear();
        assertThat(preferences.findById(to.getId()).orElseThrow().getSubtitle()).isEqualTo("French");
        assertThat(preferences.findById(from.getId())).isEmpty();
    }

    @Test
    void theOwnersForcedNeedChoiceMovesToTheSurvivorWhenItHasNone() {
        Film from = film("From", "From.m4v");
        from.setForcedNeedOwner(ForcedNeedChoice.NEEDED);
        films.saveAndFlush(from);
        Film to = film("To", "To.m4v");

        ingest.merge(from.getId(), to.getId());

        em.flush();
        em.clear();
        assertThat(films.findById(to.getId()).orElseThrow().getForcedNeedOwner()).isEqualTo(ForcedNeedChoice.NEEDED);
    }

    @Test
    void theSurvivorsOwnForcedNeedChoiceWinsWhenBothFilmsHaveOne() {
        Film from = film("From", "From.m4v");
        from.setForcedNeedOwner(ForcedNeedChoice.NEEDED);
        films.saveAndFlush(from);
        Film to = film("To", "To.m4v");
        to.setForcedNeedOwner(ForcedNeedChoice.NOT_NEEDED);
        films.saveAndFlush(to);

        ingest.merge(from.getId(), to.getId());

        em.flush();
        em.clear();
        assertThat(films.findById(to.getId()).orElseThrow().getForcedNeedOwner())
            .isEqualTo(ForcedNeedChoice.NOT_NEEDED);
    }

    @Test
    void aSurvivorKeepsItsChoiceAndANullSourceChoiceChangesNothing() {
        Film from = film("From", "From.m4v");
        Film to = film("To", "To.m4v");
        to.setForcedNeedOwner(ForcedNeedChoice.NEEDED);
        films.saveAndFlush(to);

        ingest.merge(from.getId(), to.getId());

        em.flush();
        em.clear();
        assertThat(films.findById(to.getId()).orElseThrow().getForcedNeedOwner()).isEqualTo(ForcedNeedChoice.NEEDED);
    }
}
