package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.CLOCK;
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
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.library.LibraryRoot;
import gregochr.cinefilm.library.LibraryScanner.Found;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.subtitle.ForcedDownloadBlock;
import gregochr.cinefilm.subtitle.ForcedDownloadBlockRepository;
import gregochr.cinefilm.subtitle.ForcedDownloadCheck;
import gregochr.cinefilm.subtitle.ForcedDownloadCheckRepository;
import gregochr.cinefilm.subtitle.SidecarSync;
import gregochr.cinefilm.subtitle.SubtitleAdminService;
import gregochr.cinefilm.subtitle.SubtitleCheckBase;
import gregochr.cinefilm.subtitle.SubtitleDtos.SidecarDto;
import gregochr.cinefilm.subtitle.SubtitleKind;
import gregochr.cinefilm.subtitle.SubtitleSidecar;
import gregochr.cinefilm.subtitle.SubtitleSidecarRepository;
import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * V19 on PostgreSQL: the download columns on {@code subtitle_sidecar}, the comparison table of a download and the
 * block table, their constraints and cascades, the scan's sync with downloads present, the owner's removal through the
 * real service, the film's IMDb-id cleanup statement, and the Admin list's JSON.
 */
@Transactional
class ForcedDownloadIntegrationTest extends IntegrationTestBase {

    private static final String CUES = "1\n00:00:10,000 --> 00:00:12,000\nHello\n\n";

    @Autowired
    private FilmRepository films;

    @Autowired
    private MediaFileRepository mediaFiles;

    @Autowired
    private SubtitleSidecarRepository sidecars;

    @Autowired
    private OpenSubtitlesReferenceRepository references;

    @Autowired
    private ForcedDownloadCheckRepository downloadChecks;

    @Autowired
    private ForcedDownloadBlockRepository blocks;

    @Autowired
    private SubtitleAdminService admin;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper mapper;

    private Film saved(String title, String imdbId) {
        Film film = film(null, title, 2008, NOW);
        film.setImdbId(imdbId);
        Edition edition = edition(film, null, "Original", 90 * MIN, null);
        MediaFile file = file(edition, title + ".m4v");
        file.setSizeBytes(4_000_000_000L);
        file.setModifiedAt(NOW);
        return films.saveAndFlush(film);
    }

    private static MediaFile fileOf(Film film) {
        return film.getEditions().get(0).originalParts().get(0);
    }

    private SubtitleSidecar download(Film film, long osFileId) {
        SubtitleSidecar row = new SubtitleSidecar();
        row.setOrigin(SubtitleSidecar.Origin.OPENSUBTITLES);
        row.setPath("opensubtitles:" + film.getEditions().get(0).getId() + ":" + osFileId);
        row.setSizeBytes(CUES.length());
        row.setModifiedAt(NOW);
        row.setMediaFileId(fileOf(film).getId());
        row.setWholeEdition(true);
        row.setCuesText(CUES);
        row.setParseReport("Downloaded from OpenSubtitles");
        row.setCueCount(1);
        row.setLastEndMs(12_000);
        row.setOpenSubtitlesFileId(osFileId);
        row.setRelease("Taken.2008.BluRay");
        row.setImdbId(film.getImdbId());
        row.setDownloadedAt(NOW);
        row.setUpdatedAt(NOW);
        return sidecars.saveAndFlush(row);
    }

    private SubtitleSidecar owners(Film film) {
        SubtitleSidecar row = new SubtitleSidecar();
        row.setPath(film.getTitle() + ".en.forced.srt");
        row.setSizeBytes(100);
        row.setModifiedAt(NOW);
        row.setMediaFileId(fileOf(film).getId());
        row.setCuesText(CUES);
        row.setParseReport("No problems found");
        row.setCueCount(1);
        row.setUpdatedAt(NOW);
        return sidecars.saveAndFlush(row);
    }

    private OpenSubtitlesReference reference(Film film, long osFileId) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setFilmId(film.getId());
        reference.setImdbId(film.getImdbId());
        reference.setOsFileId(osFileId);
        reference.setRelease("Release " + osFileId);
        reference.setCuesText(CUES);
        reference.setCueCount(1);
        reference.setFetchedAt(NOW);
        return references.saveAndFlush(reference);
    }

    private ForcedDownloadCheck check(SubtitleSidecar download, OpenSubtitlesReference reference) {
        ForcedDownloadCheck row = new ForcedDownloadCheck();
        row.setSidecarId(download.getId());
        row.setServedVersion("v".repeat(1000));
        row.setReferenceId(reference.getId());
        row.setVerdict("CHECK");
        row.setMatchedShare(0.33);
        row.setServedCount(12);
        row.setReferenceCount(41);
        row.setBestOffsetMs(-1_200);
        row.setReasons("r".repeat(1000));
        row.setCheckedAt(NOW);
        row.setBestFileId(5011L);
        return downloadChecks.saveAndFlush(row);
    }

    @Test
    void aDownloadRoundTripsEveryColumnAndAnOwnersFileDefaultsToFile() {
        Film film = saved("Taken", "tt0936501");
        SubtitleSidecar download = download(film, 5011);
        SubtitleSidecar owner = owners(film);
        entityManager.clear();

        SubtitleSidecar loaded = sidecars.findById(download.getId()).orElseThrow();
        SubtitleSidecar file = sidecars.findById(owner.getId()).orElseThrow();

        assertThat(loaded.getOrigin()).isEqualTo(SubtitleSidecar.Origin.OPENSUBTITLES);
        assertThat(loaded.getOpenSubtitlesFileId()).isEqualTo(5011L);
        assertThat(loaded.getRelease()).isEqualTo("Taken.2008.BluRay");
        assertThat(loaded.getImdbId()).isEqualTo("tt0936501");
        assertThat(loaded.getDownloadedAt()).isEqualTo(NOW);
        assertThat(loaded.isWholeEdition()).isTrue();
        assertThat(loaded.getPath()).isEqualTo("opensubtitles:" + film.getEditions().get(0).getId() + ":5011");
        assertThat(file.getOrigin()).isEqualTo(SubtitleSidecar.Origin.FILE);
        assertThat(file.getOpenSubtitlesFileId()).isNull();
        assertThat(file.getRelease()).isNull();
        assertThat(file.getImdbId()).isNull();
        assertThat(file.getDownloadedAt()).isNull();
    }

    // On PostgreSQL a failed statement aborts the transaction, so each expected violation is the last statement of its
    // own test.

    @Test
    void anOriginThatIsNotFileOrOpenSubtitlesIsRefused() {
        Film film = saved("Taken", "tt0936501");
        SubtitleSidecar owner = owners(film);

        assertThatThrownBy(() -> jdbc.update("update subtitle_sidecar set origin = 'ROBOT' where id = ?",
            owner.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aDownloadNeedsItsOpenSubtitlesFileId() {
        Film film = saved("Taken", "tt0936501");
        SubtitleSidecar download = download(film, 5011);

        assertThatThrownBy(() -> jdbc.update(
            "update subtitle_sidecar set opensubtitles_file_id = null where id = ?", download.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aDownloadsComparisonRoundTripsFullLengthTextAndDeletingTheDownloadDeletesIt() {
        Film film = saved("Taken", "tt0936501");
        SubtitleSidecar download = download(film, 5011);
        ForcedDownloadCheck row = check(download, reference(film, 5012));
        entityManager.clear();

        ForcedDownloadCheck loaded = downloadChecks.findBySidecarId(download.getId()).orElseThrow();

        assertThat(loaded.getVerdict()).isEqualTo("CHECK");
        assertThat(loaded.getServedVersion()).hasSize(1000);
        assertThat(loaded.getReasons()).hasSize(1000);
        assertThat(loaded.getBestOffsetMs()).isEqualTo(-1_200);
        assertThat(loaded.getBestFileId()).isEqualTo(5011L);
        assertThat(loaded.getRulesVersion()).isEqualTo(SubtitleCheckBase.RULES_VERSION);

        entityManager.clear();
        sidecars.deleteById(download.getId());
        sidecars.flush();
        entityManager.clear();

        assertThat(downloadChecks.findById(row.getId())).isEmpty();
    }

    @Test
    void aDownloadHasOnlyOneComparison() {
        Film film = saved("Taken", "tt0936501");
        SubtitleSidecar download = download(film, 5011);
        check(download, reference(film, 5012));
        OpenSubtitlesReference other = reference(film, 5013);

        assertThatThrownBy(() -> check(download, other)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aBlockedFileIsRecordedOncePerEditionAndImdbId() {
        Film film = saved("Taken", "tt0936501");
        ForcedDownloadBlock block = new ForcedDownloadBlock();
        block.setEditionId(film.getEditions().get(0).getId());
        block.setImdbId("tt0936501");
        block.setOsFileId(5011);
        block.setRemovedAt(NOW);
        blocks.saveAndFlush(block);
        entityManager.clear();

        assertThat(blocks.existsByEditionIdAndImdbIdAndOsFileIdAndKind(
                film.getEditions().get(0).getId(), "tt0936501", 5011, SubtitleKind.FORCED))
            .isTrue();
        assertThat(blocks.findByEditionIdInAndKind(List.of(film.getEditions().get(0).getId()), SubtitleKind.FORCED))
            .hasSize(1);
        ForcedDownloadBlock again = new ForcedDownloadBlock();
        again.setEditionId(film.getEditions().get(0).getId());
        again.setImdbId("tt0936501");
        again.setOsFileId(5011);
        again.setRemovedAt(NOW);
        assertThatThrownBy(() -> blocks.saveAndFlush(again)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theScansSyncLeavesDownloadsAloneWhateverItFinds(@TempDir Path library) {
        Film film = saved("Taken", "tt0936501");
        SubtitleSidecar download = download(film, 5011);
        SidecarSync sync = new SidecarSync(sidecars, mediaFiles, new LibraryRoot(library.toString()), CLOCK);

        sync.sync(new Found(List.of(), 0, List.of(), List.of()));
        entityManager.flush();
        entityManager.clear();

        SubtitleSidecar after = sidecars.findById(download.getId()).orElseThrow();
        assertThat(after.getPresence().name()).isEqualTo("PRESENT");
        assertThat(after.getMediaFileId()).isEqualTo(fileOf(film).getId());
        assertThat(after.isWholeEdition()).isTrue();
        assertThat(after.getCuesText()).isEqualTo(CUES);
        assertThat(after.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void theOwnersRemovalDeletesTheDownloadItsComparisonAndBlocksTheFile() {
        Film film = saved("Taken", "tt0936501");
        SubtitleSidecar download = download(film, 5011);
        ForcedDownloadCheck row = check(download, reference(film, 5012));
        entityManager.clear();

        admin.remove(download.getId());
        entityManager.flush();
        entityManager.clear();

        assertThat(sidecars.findById(download.getId())).isEmpty();
        assertThat(downloadChecks.findById(row.getId())).isEmpty();
        assertThat(blocks.existsByEditionIdAndImdbIdAndOsFileIdAndKind(
                film.getEditions().get(0).getId(), "tt0936501", 5011, SubtitleKind.FORCED))
            .isTrue();
        assertThat(references.findAll()).hasSize(1);
    }

    @Test
    void theImdbIdCleanupRemovesOnlyThatFilmsDownloads() {
        Film heat = saved("Heat", "tt0113277");
        Film alien = saved("Alien", "tt0078748");
        SubtitleSidecar gone = download(heat, 6011);
        SubtitleSidecar owner = owners(heat);
        SubtitleSidecar kept = download(alien, 7011);
        entityManager.clear();

        int removed = films.deleteOpenSubtitlesDownloads(heat.getId());
        entityManager.clear();

        assertThat(removed).isEqualTo(1);
        assertThat(sidecars.findById(gone.getId())).isEmpty();
        assertThat(sidecars.findById(owner.getId())).isPresent();
        assertThat(sidecars.findById(kept.getId())).isPresent();
    }

    @Test
    void theAdminListShowsADownloadsOriginReleaseFileIdAndReasonAsJson() throws Exception {
        Film film = saved("Taken", "tt0936501");
        SubtitleSidecar download = download(film, 5011);
        entityManager.clear();

        List<SidecarDto> listed = admin.list();
        JsonNode json = mapper.readTree(mapper.writeValueAsString(listed)).get(0);

        assertThat(json.get("id").asLong()).isEqualTo(download.getId());
        assertThat(json.get("origin").asString()).isEqualTo("OPENSUBTITLES");
        assertThat(json.get("release").asString()).isEqualTo("Taken.2008.BluRay");
        assertThat(json.get("openSubtitlesFileId").asLong()).isEqualTo(5011L);
        assertThat(json.get("servedReason").asString()).isEqualTo("Plays: nothing else is available; not checked yet");
        assertThat(json.get("replacedByDownload").asBoolean()).isFalse();
        assertThat(json.get("whole").asBoolean()).isTrue();
        assertThat(json.get("served").asBoolean()).isTrue();
    }
}
