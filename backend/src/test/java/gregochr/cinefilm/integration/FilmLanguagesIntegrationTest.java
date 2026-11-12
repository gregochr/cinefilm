package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.NeedRow;
import gregochr.cinefilm.film.FilmRepository.TmdbRef;
import gregochr.cinefilm.film.ForcedNeedChoice;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** V14's columns and check constraint on PostgreSQL, and the languages backfill's targeted update. */
@Transactional
class FilmLanguagesIntegrationTest extends IntegrationTestBase {

    private static final Instant LATER = NOW.plusSeconds(60);

    @Autowired
    private FilmRepository films;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    private Film saved(String title, Long tmdbId) {
        Film film = film(null, title, 2000, NOW);
        film.setTmdbId(tmdbId);
        return films.saveAndFlush(film);
    }

    @Test
    void theLanguageColumnsAndTheOwnersChoiceRoundTrip() {
        Film film = film(null, "Languages Round Trip", 2008, NOW);
        film.setOriginalLanguage("en");
        film.setSpokenLanguages("en,fr,sq");
        film.setLanguagesFetchedAt(NOW);
        film.setForcedNeedOwner(ForcedNeedChoice.NOT_NEEDED);
        Long id = films.saveAndFlush(film).getId();
        // The test is transactional: without clearing, findById would hand back this same instance, not the row.
        entityManager.clear();

        Film loaded = films.findById(id).orElseThrow();

        assertThat(loaded.getOriginalLanguage()).isEqualTo("en");
        assertThat(loaded.getSpokenLanguages()).isEqualTo("en,fr,sq");
        assertThat(loaded.getLanguagesFetchedAt()).isEqualTo(NOW);
        assertThat(loaded.getForcedNeedOwner()).isEqualTo(ForcedNeedChoice.NOT_NEEDED);
    }

    @Test
    void theDatabaseRefusesAnOwnerChoiceThatIsNeitherNeededNorNotNeeded() {
        Long id = saved("Check Constraint", null).getId();

        assertThatThrownBy(() -> jdbc.update("update film set forced_need_owner = 'MAYBE' where id = ?", id))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void theTargetedUpdateStampsTheFilmForItsTmdbIdAndLeavesARematchedOneAlone() {
        Film due = saved("Due For Languages", 770_001L);
        Film rematched = saved("Rematched Meanwhile", 770_002L);

        int changed = films.saveLanguages(due.getId(), 770_001L, "en", "en,es", LATER);
        int refused = films.saveLanguages(rematched.getId(), 770_999L, "en", "en", LATER);
        entityManager.clear();

        assertThat(changed).isEqualTo(1);
        assertThat(refused).isZero();
        Film stamped = films.findById(due.getId()).orElseThrow();
        assertThat(stamped.getOriginalLanguage()).isEqualTo("en");
        assertThat(stamped.getSpokenLanguages()).isEqualTo("en,es");
        assertThat(stamped.getLanguagesFetchedAt()).isEqualTo(LATER);
        assertThat(films.findById(rematched.getId()).orElseThrow().getLanguagesFetchedAt()).isNull();
    }

    @Test
    void theBackfillAndNeedQueriesRunOnPostgres() {
        Film due = saved("Backfill Query", 770_003L);

        List<TmdbRef> found = films.findWithoutLanguages();
        List<NeedRow> rows = films.findNeedRows();

        assertThat(found).contains(new TmdbRef(due.getId(), 770_003L));
        assertThat(rows).extracting(NeedRow::id).contains(due.getId());
    }
}
