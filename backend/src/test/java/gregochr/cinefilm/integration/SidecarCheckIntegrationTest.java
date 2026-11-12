package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesBudget;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.subtitle.ForcedSubtitleCheck;
import gregochr.cinefilm.subtitle.ForcedSubtitleCheckRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** V16's tables on PostgreSQL: the unique keys, the cascades and the budget's atomic statements. */
@Transactional
class SidecarCheckIntegrationTest extends IntegrationTestBase {

    @Autowired
    private FilmRepository films;

    @Autowired
    private EditionRepository editions;

    @Autowired
    private OpenSubtitlesReferenceRepository references;

    @Autowired
    private ForcedSubtitleCheckRepository checks;

    @Autowired
    private OpenSubtitlesBudget budget;

    @Autowired
    private EntityManager entityManager;

    private Film saved() {
        Film film = film(null, "Taken", 2008, NOW);
        Edition edition = edition(film, null, "Original", 90 * MIN, null);
        file(edition, "Taken.m4v");
        film.setImdbId("tt0936501");
        return films.saveAndFlush(film);
    }

    private OpenSubtitlesReference reference(Film film, long fileId) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setFilmId(film.getId());
        reference.setImdbId("tt0936501");
        reference.setOsFileId(fileId);
        reference.setRelease("x".repeat(200));
        reference.setCuesText("1\n00:00:01,000 --> 00:00:02,000\nHi\n\n");
        reference.setCueCount(1);
        reference.setFetchedAt(NOW);
        return references.saveAndFlush(reference);
    }

    private ForcedSubtitleCheck check(Film film, OpenSubtitlesReference reference) {
        ForcedSubtitleCheck row = new ForcedSubtitleCheck();
        row.setEditionId(film.getEditions().get(0).getId());
        row.setServedVersion("v1|1|2@0");
        row.setReferenceId(reference.getId());
        row.setVerdict("AGREES");
        row.setMatchedShare(0.95);
        row.setServedCount(12);
        row.setReferenceCount(13);
        row.setBestOffsetMs(-300);
        row.setReasons("r".repeat(1000));
        row.setCheckedAt(NOW);
        return checks.saveAndFlush(row);
    }

    @Test
    void aReferenceAndACheckRoundTripWithFullLengthText() {
        Film film = saved();
        ForcedSubtitleCheck row = check(film, reference(film, 5011));
        entityManager.clear();

        ForcedSubtitleCheck loaded = checks.findByEditionId(row.getEditionId()).orElseThrow();

        assertThat(loaded.getVerdict()).isEqualTo("AGREES");
        assertThat(loaded.getMatchedShare()).isEqualTo(0.95);
        assertThat(loaded.getBestOffsetMs()).isEqualTo(-300);
        assertThat(loaded.getReasons()).hasSize(1000);
        assertThat(loaded.getCheckedAt()).isEqualTo(NOW);
        assertThat(references.findByFilmIdAndOsFileId(film.getId(), 5011).orElseThrow().getRelease()).hasSize(200);
    }

    @Test
    void aFilmHasOneReferencePerFileAndAnEditionOneCheck() {
        Film film = saved();
        OpenSubtitlesReference reference = reference(film, 5011);
        check(film, reference);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> reference(film, 5011))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        entityManager.clear();
    }

    @Test
    void deletingAnEditionDeletesItsCheckAndDeletingAFilmItsReferences() {
        Film film = saved();
        OpenSubtitlesReference reference = reference(film, 5011);
        ForcedSubtitleCheck row = check(film, reference);
        entityManager.clear();

        entityManager.createNativeQuery("delete from media_file where edition_id = :id")
            .setParameter("id", row.getEditionId()).executeUpdate();
        editions.deleteByIds(java.util.List.of(row.getEditionId()));
        entityManager.clear();
        assertThat(checks.findByEditionId(row.getEditionId())).isEmpty();
        assertThat(references.findById(reference.getId())).isPresent();

        entityManager.createNativeQuery("delete from film where id = :id").setParameter("id", film.getId())
            .executeUpdate();
        entityManager.clear();
        assertThat(references.findById(reference.getId())).isEmpty();
    }

    @Test
    void theBudgetSpendsAtomicallyAndKeepsTheResetTime() {
        LocalDate day = LocalDate.of(2024, 3, 31);
        Instant reset = Instant.parse("2024-04-01T00:00:00Z");

        assertThat(budget.take(day, 2)).isTrue();
        assertThat(budget.take(day, 2)).isTrue();
        assertThat(budget.take(day, 2)).isFalse();
        budget.refund(day);
        assertThat(budget.take(day, 2)).isTrue();
        budget.exhaust(day, 2, reset);

        assertThat(budget.blockedUntil(reset.minusSeconds(1))).isEqualTo(reset);
        assertThat(budget.blockedUntil(reset)).isNull();
    }

    @Test
    void theBestResultQueryReturnsOnlyFilmsSearchedUnderTheirCurrentImdbId() {
        Film film = saved();
        film.setOsSearchedImdbId("tt0936501");
        film.setOsForcedCount(2);
        film.setOsBest("{\"id\":1,\"fileId\":2}");
        films.saveAndFlush(film);

        assertThat(films.findOpenSubtitlesBest()).hasSize(1);
        film.setOsSearchedImdbId("tt9999999");
        films.saveAndFlush(film);
        assertThat(films.findOpenSubtitlesBest()).isEmpty();
    }
}
