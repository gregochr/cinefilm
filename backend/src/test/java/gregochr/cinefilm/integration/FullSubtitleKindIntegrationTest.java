package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.subtitle.ForcedCheckCandidate;
import gregochr.cinefilm.subtitle.ForcedCheckCandidateRepository;
import gregochr.cinefilm.subtitle.ForcedDownloadBlock;
import gregochr.cinefilm.subtitle.ForcedDownloadBlockRepository;
import gregochr.cinefilm.subtitle.SubtitleKind;
import gregochr.cinefilm.subtitle.SubtitleSidecar;
import gregochr.cinefilm.subtitle.SubtitleSidecarRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * V22 on PostgreSQL: the {@code kind} column on {@code subtitle_sidecar}, {@code forced_download_block} and
 * {@code forced_check_candidate}: existing rows are {@code FORCED}, {@code FULL} is accepted, anything else is
 * refused, a Java insert that never sets the kind still writes {@code FORCED}, and the two ledgers' unique keys are per
 * kind. Postgres aborts the transaction after a constraint violation, so each refusal is the last statement of its own
 * test.
 */
@Transactional
class FullSubtitleKindIntegrationTest extends IntegrationTestBase {

    private static final String INSERT_SIDECAR = "insert into subtitle_sidecar (path, size_bytes, modified_at, "
        + "presence, cues_text, parse_report, updated_at) values (?, 10, now(), 'PRESENT', '', '', now())";

    @Autowired
    private FilmRepository films;

    @Autowired
    private SubtitleSidecarRepository sidecars;

    @Autowired
    private ForcedDownloadBlockRepository blocks;

    @Autowired
    private ForcedCheckCandidateRepository candidates;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    private Long editionId() {
        Film film = film(null, "Taken", 2008, NOW);
        MediaFile taken = file(edition(film, null, "Original", 90 * MIN, null), "Taken.m4v");
        taken.setSizeBytes(4_000_000_000L);
        taken.setModifiedAt(NOW);
        return films.saveAndFlush(film).getEditions().get(0).getId();
    }

    private String kindOf(String path) {
        return jdbc.queryForObject("select kind from subtitle_sidecar where path = ?", String.class, path);
    }

    @Test
    void aRowInsertedWithoutAKindIsForcedAsEveryRowBeforeTheMigrationWas() {
        jdbc.update(INSERT_SIDECAR, "Taken.en.forced.srt");

        assertThat(kindOf("Taken.en.forced.srt")).isEqualTo("FORCED");
    }

    @Test
    void aFullRowCanBeInsertedAndRead() {
        jdbc.update("insert into subtitle_sidecar (path, size_bytes, modified_at, presence, cues_text, parse_report, "
            + "updated_at, kind) values ('Taken.en.srt', 10, now(), 'PRESENT', '', '', now(), 'FULL')");

        assertThat(kindOf("Taken.en.srt")).isEqualTo("FULL");
        assertThat(sidecars.findAllByKindOrderByPathAscIdAsc(SubtitleKind.FULL)).extracting(SubtitleSidecar::getPath)
            .containsExactly("Taken.en.srt");
        assertThat(sidecars.findAllByKindOrderByPathAscIdAsc(SubtitleKind.FORCED)).isEmpty();
    }

    @Test
    void aJavaInsertThatNeverSetsTheKindWritesForcedAndFullRoundTripsThroughHibernate() {
        SubtitleSidecar unset = new SubtitleSidecar();
        unset.setPath("Taken.en.forced.srt");
        unset.setSizeBytes(10);
        unset.setModifiedAt(NOW);
        unset.setPresence(Presence.PRESENT);
        unset.setUpdatedAt(NOW);
        SubtitleSidecar full = new SubtitleSidecar();
        full.setPath("Taken.en.srt");
        full.setSizeBytes(10);
        full.setModifiedAt(NOW);
        full.setPresence(Presence.PRESENT);
        full.setUpdatedAt(NOW);
        full.setKind(SubtitleKind.FULL);
        sidecars.saveAllAndFlush(List.of(unset, full));
        entityManager.clear();

        assertThat(kindOf("Taken.en.forced.srt")).isEqualTo("FORCED");
        assertThat(sidecars.findAllByKindOrderByPathAscIdAsc(SubtitleKind.FULL)).extracting(SubtitleSidecar::getPath)
            .containsExactly("Taken.en.srt");
    }

    @Test
    void theBlockAndTheLedgerDefaultToForcedAndHoldTheSameFileOncePerKind() {
        Long edition = editionId();
        jdbc.update("insert into forced_download_block (edition_id, imdb_id, os_file_id, removed_at) "
            + "values (?, 'tt0936501', 5011, now())", edition);
        jdbc.update("insert into forced_check_candidate (edition_id, imdb_id, os_file_id, tried_at) "
            + "values (?, 'tt0936501', 5011, now())", edition);
        jdbc.update("insert into forced_download_block (edition_id, imdb_id, os_file_id, removed_at, kind) "
            + "values (?, 'tt0936501', 5011, now(), 'FULL')", edition);
        jdbc.update("insert into forced_check_candidate (edition_id, imdb_id, os_file_id, tried_at, kind) "
            + "values (?, 'tt0936501', 5011, now(), 'FULL')", edition);

        assertThat(blocks.findByEditionIdInAndKind(List.of(edition), SubtitleKind.FORCED)).hasSize(1);
        assertThat(blocks.findByEditionIdInAndKind(List.of(edition), SubtitleKind.FULL)).hasSize(1);
        assertThat(candidates.findByEditionIdInAndKind(List.of(edition), SubtitleKind.FORCED)).hasSize(1);
        assertThat(candidates.findByEditionIdInAndKind(List.of(edition), SubtitleKind.FULL)).hasSize(1);
        assertThat(blocks.existsByEditionIdAndImdbIdAndOsFileIdAndKind(edition, "tt0936501", 5011,
            SubtitleKind.FULL)).isTrue();
    }

    @Test
    void aJavaInsertOfABlockOrACandidateThatNeverSetsTheKindWritesForced() {
        Long edition = editionId();
        ForcedDownloadBlock block = new ForcedDownloadBlock();
        block.setEditionId(edition);
        block.setImdbId("tt0936501");
        block.setOsFileId(5011);
        block.setRemovedAt(NOW);
        blocks.saveAndFlush(block);
        ForcedCheckCandidate tried = new ForcedCheckCandidate();
        tried.setEditionId(edition);
        tried.setImdbId("tt0936501");
        tried.setOsFileId(5011);
        tried.setTriedAt(NOW);
        candidates.saveAndFlush(tried);

        assertThat(jdbc.queryForList("select kind from forced_download_block where edition_id = ?", String.class,
            edition)).containsExactly("FORCED");
        assertThat(jdbc.queryForList("select kind from forced_check_candidate where edition_id = ?", String.class,
            edition)).containsExactly("FORCED");
    }

    @Test
    void theSameBlockOfTheSameKindTwiceIsRefused() {
        Long edition = editionId();
        jdbc.update("insert into forced_download_block (edition_id, imdb_id, os_file_id, removed_at, kind) "
            + "values (?, 'tt0936501', 5011, now(), 'FULL')", edition);

        assertThatThrownBy(() -> jdbc.update("insert into forced_download_block (edition_id, imdb_id, os_file_id, "
            + "removed_at, kind) values (?, 'tt0936501', 5011, now(), 'FULL')", edition))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSameCandidateOfTheSameKindTwiceIsRefused() {
        Long edition = editionId();
        jdbc.update("insert into forced_check_candidate (edition_id, imdb_id, os_file_id, tried_at) "
            + "values (?, 'tt0936501', 5011, now())", edition);

        assertThatThrownBy(() -> jdbc.update("insert into forced_check_candidate (edition_id, imdb_id, os_file_id, "
            + "tried_at) values (?, 'tt0936501', 5011, now())", edition))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aBlockWithAnInvalidKindIsRefused() {
        Long edition = editionId();

        assertThatThrownBy(() -> jdbc.update("insert into forced_download_block (edition_id, imdb_id, os_file_id, "
            + "removed_at, kind) values (?, 'tt0936501', 5011, now(), 'ROBOT')", edition))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aCandidateWithAnInvalidKindIsRefused() {
        Long edition = editionId();

        assertThatThrownBy(() -> jdbc.update("insert into forced_check_candidate (edition_id, imdb_id, os_file_id, "
            + "tried_at, kind) values (?, 'tt0936501', 5011, now(), 'ROBOT')", edition))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSidecarWithAnInvalidKindIsRefused() {
        assertThatThrownBy(() -> jdbc.update("insert into subtitle_sidecar (path, size_bytes, modified_at, presence, "
            + "cues_text, parse_report, updated_at, kind) values ('Taken.en.srt', 10, now(), 'PRESENT', '', '', "
            + "now(), 'ROBOT')")).isInstanceOf(DataIntegrityViolationException.class);
    }
}
