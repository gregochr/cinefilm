package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.film.AudioStream;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.FullNeedRow;
import gregochr.cinefilm.film.FullNeedChoice;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.MediaFileRepository.AudioRow;
import gregochr.cinefilm.subtitle.FullNeedDtos.FullNeedFilmDto;
import gregochr.cinefilm.subtitle.FullNeedService;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** V21's column and check constraint on PostgreSQL, and the full-need queries and service over real rows. */
@Transactional
class FullNeedIntegrationTest extends IntegrationTestBase {

    @Autowired
    private FilmRepository films;

    @Autowired
    private MediaFileRepository files;

    @Autowired
    private FullNeedService service;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    private Film saved(String title, String original, String... audioLabels) {
        Film film = film(null, title, 2008, NOW);
        film.setTmdbId(Math.abs(title.hashCode()) + 5_000_000L);
        film.setOriginalLanguage(original);
        film.setSpokenLanguages(original);
        film.setLanguagesFetchedAt(NOW);
        Edition edition = edition(film, null, "Original", 90 * MIN, null);
        MediaFile file = file(edition, title + ".m4v");
        file.getAudioStreams().clear();
        int index = 0;
        for (String label : audioLabels) {
            AudioStream stream = new AudioStream();
            stream.setMediaFile(file);
            stream.setIndex(index++);
            stream.setDefault(index == audioLabels.length);
            stream.setLanguage(label);
            file.getAudioStreams().add(stream);
        }
        return films.saveAndFlush(film);
    }

    @Test
    void theOwnersChoiceRoundTrips() {
        Film film = saved("Full Need Round Trip", "fr", "French");
        film.setFullNeedOwner(FullNeedChoice.NOT_NEEDED);
        films.saveAndFlush(film);
        entityManager.clear();

        assertThat(films.findById(film.getId()).orElseThrow().getFullNeedOwner())
            .isEqualTo(FullNeedChoice.NOT_NEEDED);
    }

    @Test
    void theNeedRowsAndTheAudioRowsRunOnPostgres() {
        Film film = saved("Full Need Queries", "fr", "English", "French");
        entityManager.clear();

        List<FullNeedRow> rows = films.findFullNeedRows();
        List<AudioRow> audio = files.findAudioRows(List.of(film.getId()));

        assertThat(rows).extracting(FullNeedRow::id).contains(film.getId());
        assertThat(audio).extracting(AudioRow::language).containsExactly("English", "French");
        assertThat(audio).extracting(AudioRow::isDefault).containsExactly(false, true);
    }

    @Test
    void theListWorksOverRealRows() {
        Film film = saved("Full Need Service", "ja", "Japanese");
        entityManager.clear();

        FullNeedFilmDto listed = service.list().films().stream()
            .filter(f -> f.filmId().equals(film.getId())).findFirst().orElseThrow();
        assertThat(listed.need()).isEqualTo("NEEDED");
        assertThat(listed.editions()).hasSize(1);
        assertThat(listed.editions().get(0).audio()).isEqualTo("Japanese");
    }

    /** A constraint violation aborts the Postgres transaction, so nothing may follow it in this test. */
    @Test
    void theDatabaseRefusesAnOwnerChoiceThatIsNeitherNeededNorNotNeeded() {
        Long id = saved("Full Need Check", "fr", "French").getId();

        assertThatThrownBy(() -> jdbc.update("update film set full_need_owner = 'MAYBE' where id = ?", id))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
