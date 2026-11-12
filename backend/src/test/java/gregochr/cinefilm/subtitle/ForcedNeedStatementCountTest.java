package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.NeedListDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The Admin list costs a fixed number of statements however many films have sidecar evidence (no N+1). */
class ForcedNeedStatementCountTest extends SubtitleTestBase {

    @Autowired
    private ForcedNeedService service;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory factory;

    private Statistics statistics;

    @BeforeEach
    void enableStatistics() {
        statistics = factory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    @AfterEach
    void disableStatistics() {
        statistics.setStatisticsEnabled(false);
    }

    private void evidenceFilm(String title, int parts) {
        Film film = filmWithParts(title, parts, 60);
        film.setTmdbId(film.getId() + 1000);
        film.setOriginalLanguage("en");
        film.setSpokenLanguages("en");
        film.setLanguagesFetchedAt(NOW);
        films.saveAndFlush(film);
        for (int part = 1; part <= parts; part++) {
            sidecar(title + " - pt" + part + ".en.forced.srt", fileId(film, part), false);
        }
    }

    /** The statements one list call prepares, from a cold persistence context. */
    private long statementsOfOneList(int expectedServed) {
        entityManager.flush();
        entityManager.clear();
        statistics.clear();

        NeedListDto list = service.list();

        assertThat(list.films().stream().filter(f -> f.hasForcedSubtitle()).count()).isEqualTo(expectedServed);
        return statistics.getPrepareStatementCount();
    }

    @Test
    void threeEvidenceFilmsCostNoMoreStatementsThanOne() {
        evidenceFilm("One", 1);
        long withOne = statementsOfOneList(1);

        evidenceFilm("Two", 2);
        evidenceFilm("Three", 1);
        long withThree = statementsOfOneList(3);

        assertThat(withOne).isPositive();
        assertThat(withThree).isEqualTo(withOne);
    }
}
