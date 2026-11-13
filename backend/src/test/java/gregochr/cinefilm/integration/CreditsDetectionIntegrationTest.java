package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.credits.CreditsDetection;
import gregochr.cinefilm.credits.CreditsDetectionRepository;
import gregochr.cinefilm.credits.CreditsDetectionService;
import gregochr.cinefilm.credits.CreditsDetectionService.DueFile;
import gregochr.cinefilm.credits.CreditsDetectionService.SaveOutcome;
import gregochr.cinefilm.credits.CreditsDetectionRepository.Stored;
import gregochr.cinefilm.credits.CreditsResult;
import gregochr.cinefilm.credits.CreditsVerdict;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.MediaFile;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * V20 on PostgreSQL: the table's columns and types, its verdict check, its cascade with the media file, and a save and
 * the due-file queries through the real service.
 */
@Transactional
class CreditsDetectionIntegrationTest extends IntegrationTestBase {

    @Autowired
    private FilmRepository films;

    @Autowired
    private CreditsDetectionRepository detections;

    @Autowired
    private CreditsDetectionService service;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    /** A film of {@code parts} one-hour files, each with a size and a modification time. */
    private Film saved(String title, int parts) {
        Film film = film(null, title, 2008, NOW);
        Edition edition = edition(film, null, "Original", parts * 60 * MIN, null);
        for (int i = 1; i <= parts; i++) {
            MediaFile file = file(edition, title + " - pt" + i + ".m4v");
            file.setPartNumber(i);
            file.setDurationMs(60 * MIN);
            file.setSizeBytes(4_000_000_000L + i);
            file.setModifiedAt(NOW);
        }
        return films.saveAndFlush(film);
    }

    private static MediaFile part(Film film, int number) {
        return film.getEditions().get(0).originalParts().get(number - 1);
    }

    private CreditsDetection row(MediaFile file, CreditsVerdict verdict, Long offsetMs) {
        CreditsDetection row = new CreditsDetection();
        row.setMediaFileId(file.getId());
        row.setFingerprint("4000000001:" + NOW.toEpochMilli());
        row.setVerdict(verdict);
        row.setOffsetMs(offsetMs);
        row.setReasons("r".repeat(2000));
        row.setCheckedAt(NOW);
        return detections.saveAndFlush(row);
    }

    @Test
    void theTableHasTheColumnsAndTypesTheEntityNeeds() {
        List<String> columns = jdbc.queryForList(
            "SELECT column_name || ':' || data_type || ':' || is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'credits_detection' ORDER BY column_name", String.class);

        assertThat(columns).containsExactly("analysis_version:integer:NO", "checked_at:timestamp with time zone:NO",
            "fingerprint:character varying:NO", "media_file_id:bigint:NO", "offset_ms:bigint:YES",
            "reasons:character varying:NO", "verdict:character varying:NO");
    }

    @Test
    void aRowInsertedWithoutTheVersionColumnReadsBackAsVersionOne() {
        Film film = saved("Taken", 1);
        jdbc.update("insert into credits_detection (media_file_id, fingerprint, verdict, reasons, checked_at) "
            + "values (?, 'x', 'NOT_FOUND', 'r', now())", part(film, 1).getId());
        entityManager.clear();

        assertThat(detections.findById(part(film, 1).getId()).orElseThrow().getAnalysisVersion()).isEqualTo(1);
    }

    @Test
    void findAllStoredReturnsTheFingerprintAndTheVersion() {
        Film film = saved("Taken", 1);
        CreditsDetection stored = row(part(film, 1), CreditsVerdict.FOUND, 5_000L);
        stored.setAnalysisVersion(7);
        detections.saveAndFlush(stored);
        entityManager.clear();

        assertThat(detections.findAllStored()).containsExactly(
            new Stored(part(film, 1).getId(), "4000000001:" + NOW.toEpochMilli(), 7));
    }

    @Test
    void aFileWhoseRowIsFromAnOlderAnalysisVersionIsDueAgainAndSavingBringsTheVersionUp() {
        Film film = saved("Taken", 1);
        MediaFile file = part(film, 1);
        CreditsDetection old = row(file, CreditsVerdict.FOUND, 5_000L);
        old.setFingerprint(file.fingerprint());
        old.setAnalysisVersion(1);
        detections.saveAndFlush(old);
        entityManager.clear();

        List<DueFile> due = service.dueFiles();
        assertThat(due).extracting(DueFile::mediaFileId).containsExactly(file.getId());

        service.save(due.get(0), new CreditsResult(CreditsVerdict.NOT_FOUND, null, List.of("None")), NOW);
        entityManager.flush();
        entityManager.clear();

        assertThat(detections.findById(file.getId()).orElseThrow().getAnalysisVersion()).isGreaterThan(1);
        assertThat(service.dueFiles()).isEmpty();
    }

    @Test
    void aResultRoundTripsWithFullLengthReasonsAndANullOffset() {
        Film film = saved("Taken", 1);
        row(part(film, 1), CreditsVerdict.NOT_FOUND, null);
        entityManager.clear();

        CreditsDetection loaded = detections.findById(part(film, 1).getId()).orElseThrow();

        assertThat(loaded.getVerdict()).isEqualTo(CreditsVerdict.NOT_FOUND);
        assertThat(loaded.getOffsetMs()).isNull();
        assertThat(loaded.getReasons()).hasSize(2000);
        assertThat(loaded.getFingerprint()).isEqualTo("4000000001:" + NOW.toEpochMilli());
        assertThat(loaded.getCheckedAt()).isEqualTo(NOW);
    }

    @Test
    void aVerdictThatIsNotOneOfTheThreeIsRefused() {
        Film film = saved("Taken", 1);
        CreditsDetection row = row(part(film, 1), CreditsVerdict.FOUND, 5_000L);

        assertThatThrownBy(() -> jdbc.update("update credits_detection set verdict = 'MAYBE' where media_file_id = ?",
            row.getMediaFileId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aFileHasOneResultAndDeletingTheFileDeletesIt() {
        Film film = saved("Taken", 1);
        MediaFile file = part(film, 1);
        row(file, CreditsVerdict.FOUND, 5_000L);
        entityManager.clear();

        jdbc.update("delete from audio_stream where media_file_id = ?", file.getId());
        jdbc.update("delete from subtitle_stream where media_file_id = ?", file.getId());
        jdbc.update("delete from media_file where id = ?", file.getId());
        entityManager.clear();

        assertThat(detections.findById(file.getId())).isEmpty();
    }

    @Test
    void aResultForAFileThatDoesNotExistIsRefused() {
        assertThatThrownBy(() -> jdbc.update("insert into credits_detection (media_file_id, fingerprint, verdict, "
            + "reasons, checked_at) values (987654, 'x', 'FOUND', 'r', now())"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theServiceFindsOnlyTheLastPartDueSavesItsResultAndThenItIsNoLongerDue() {
        Film film = saved("The Long Film", 2);
        MediaFile last = part(film, 2);
        entityManager.clear();

        List<DueFile> due = service.dueFiles();
        assertThat(due).extracting(DueFile::mediaFileId).containsExactly(last.getId());

        SaveOutcome outcome = service.save(due.get(0), new CreditsResult(CreditsVerdict.FOUND, 3_500_000L,
            List.of("Text moves up")), NOW);
        entityManager.flush();
        entityManager.clear();

        assertThat(outcome).isEqualTo(SaveOutcome.SAVED);
        CreditsDetection stored = detections.findById(last.getId()).orElseThrow();
        assertThat(stored.getVerdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(stored.getOffsetMs()).isEqualTo(3_500_000L);
        assertThat(service.dueFiles()).isEmpty();
    }
}
