package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.plex.PlexCreditsMarker;
import gregochr.cinefilm.plex.PlexCreditsMarkerRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** V24 on PostgreSQL: the table's columns and types, a row's round trip, replacing a row, and the cascade. */
@Transactional
class PlexCreditsMarkerIntegrationTest extends IntegrationTestBase {

    @Autowired
    private FilmRepository films;

    @Autowired
    private PlexCreditsMarkerRepository markers;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    private MediaFile savedFile(String title) {
        Film film = film(null, title, 2008, NOW);
        Edition edition = edition(film, null, "Original", 60 * MIN, null);
        MediaFile file = file(edition, title + ".m4v");
        file.setSizeBytes(4_000_000_000L);
        file.setModifiedAt(NOW);
        return films.saveAndFlush(film).getEditions().get(0).originalParts().get(0);
    }

    private PlexCreditsMarker row(MediaFile file, long startMs) {
        PlexCreditsMarker row = new PlexCreditsMarker();
        row.setMediaFileId(file.getId());
        row.setFingerprint("4000000000:" + NOW.toEpochMilli());
        row.setStartMs(startMs);
        row.setImportedAt(NOW);
        return markers.saveAndFlush(row);
    }

    @Test
    void theTableHasTheColumnsAndTypesTheEntityNeeds() {
        List<String> columns = jdbc.queryForList(
            "SELECT column_name || ':' || data_type || ':' || is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'plex_credits_marker' ORDER BY column_name", String.class);

        assertThat(columns).containsExactly("fingerprint:character varying:NO",
            "imported_at:timestamp with time zone:NO", "media_file_id:bigint:NO", "start_ms:bigint:NO");
    }

    @Test
    void aRowRoundTripsAndIsReplacedByTheNextImport() {
        MediaFile file = savedFile("Taken");
        row(file, 3_500_000L);
        entityManager.clear();

        PlexCreditsMarker loaded = markers.findById(file.getId()).orElseThrow();
        assertThat(loaded.getStartMs()).isEqualTo(3_500_000L);
        assertThat(loaded.getFingerprint()).isEqualTo("4000000000:" + NOW.toEpochMilli());
        assertThat(loaded.getImportedAt()).isEqualTo(NOW);

        loaded.setStartMs(3_600_000L);
        markers.saveAndFlush(loaded);
        entityManager.clear();

        assertThat(markers.findAll()).hasSize(1);
        assertThat(markers.findById(file.getId()).orElseThrow().getStartMs()).isEqualTo(3_600_000L);
    }

    @Test
    void deletingTheFileDeletesItsRow() {
        MediaFile file = savedFile("Taken");
        row(file, 3_500_000L);
        entityManager.clear();

        jdbc.update("delete from audio_stream where media_file_id = ?", file.getId());
        jdbc.update("delete from subtitle_stream where media_file_id = ?", file.getId());
        jdbc.update("delete from media_file where id = ?", file.getId());
        entityManager.clear();

        assertThat(markers.findById(file.getId())).isEmpty();
    }

    @Test
    void aRowForAFileThatDoesNotExistIsRefused() {
        assertThatThrownBy(() -> jdbc.update("insert into plex_credits_marker (media_file_id, fingerprint, start_ms, "
            + "imported_at) values (987654, 'x', 1, now())")).isInstanceOf(DataIntegrityViolationException.class);
    }
}
