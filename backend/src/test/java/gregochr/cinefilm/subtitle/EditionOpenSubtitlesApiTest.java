package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesApi;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesDownload;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesSearch;
import gregochr.cinefilm.subtitle.ForcedChoice.Step;
import gregochr.cinefilm.subtitle.TimingFit.Verdict;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The picker's routes end to end on H2: a search, a pick, the pinned download and what refuses a second one. */
class EditionOpenSubtitlesApiTest extends ForcedDownloadTestBase {

    @MockitoBean
    private OpenSubtitlesApi api;

    @Autowired
    private LibraryLock lock;

    @Autowired
    private ForcedDownloadService downloadService;

    @PersistenceContext
    private EntityManager entityManager;

    private Film taken;
    private String search;
    private String download;

    @BeforeEach
    void setUp() {
        taken = searchedFilm("Taken", 1, result(5011, "Taken.2008.BluRay"), result(5012, "Taken.2008.Extended"));
        Long editionId = edition(taken).getId();
        search = "/api/admin/editions/" + editionId + "/opensubtitles";
        download = search + "/download";
        when(api.enabled()).thenReturn(true);
        when(api.downloadsEnabled()).thenReturn(true);
    }

    private void searchFindsBothFiles() {
        OpenSubtitlesResult first = result(5011, "Taken.2008.BluRay");
        OpenSubtitlesResult second = result(5012, "Taken.2008.Extended");
        when(api.searchForced(IMDB)).thenReturn(new OpenSubtitlesSearch(2, first, List.of(first, second),
            List.of(first, second)));
    }

    private void theFileDownloads(long fileId) {
        when(api.download(fileId)).thenReturn(new OpenSubtitlesDownload(
            SrtParser.parse(SubtitleTestBase.CUES.getBytes(StandardCharsets.UTF_8)), 19, null));
    }

    private String body(long fileId, String kind) {
        return "{\"fileId\":" + fileId + ",\"kind\":\"" + kind + "\"}";
    }

    @Test
    void aSearchListsTheFilmsResultsAndHowManyFilesTheEditionMayStillTake() throws Exception {
        searchFindsBothFiles();

        mvc.perform(get(search + "?kind=forced"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.imdbId").value(IMDB))
            .andExpect(jsonPath("$.kind").value("forced"))
            .andExpect(jsonPath("$.downloadsLeft").value(3))
            .andExpect(jsonPath("$.results.length()").value(2))
            .andExpect(jsonPath("$.results[0].fileId").value(5011))
            .andExpect(jsonPath("$.results[1].fileId").value(5012));
    }

    @Test
    void aPickedFileIsSavedAsTheEditionsDownloadMarkedAsThePickAndNotAsCheckedByTheOwner() throws Exception {
        searchFindsBothFiles();
        theFileDownloads(5012);
        mvc.perform(get(search + "?kind=forced")).andExpect(status().isOk());

        mvc.perform(post(download).content(body(5012, "forced")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fileId").value(5012))
            .andExpect(jsonPath("$.kind").value("forced"))
            .andExpect(jsonPath("$.release").value("Taken.2008.Extended"))
            .andExpect(jsonPath("$.downloadsLeft").value(2));

        SubtitleSidecar row = sidecars.findAll().stream().filter(SubtitleSidecar::isDownload).findFirst()
            .orElseThrow();
        assertThat(row.getPath()).isEqualTo("opensubtitles:" + edition(taken).getId() + ":5012");
        assertThat(row.getKind()).isEqualTo(SubtitleKind.FORCED);
        assertThat(row.getOwnerChosenAt()).isEqualTo(NOW);
        assertThat(row.getOwnerVerifiedAt()).isNull();
        assertThat(row.getVerifiedParts()).isNull();
        assertThat(row.getOpenSubtitlesFileId()).isEqualTo(5012L);
        assertThat(ledger.existsByEditionIdAndImdbIdAndOsFileIdAndKind(edition(taken).getId(), IMDB, 5012,
            SubtitleKind.FORCED)).isTrue();
    }

    @Test
    void aPickStillGetsItsTimingFittedAndKeepsItsMarkAfterANudge() throws Exception {
        searchFindsBothFiles();
        theFileDownloads(5012);
        mvc.perform(get(search + "?kind=forced")).andExpect(status().isOk());
        mvc.perform(post(download).content(body(5012, "forced"))).andExpect(status().isOk());
        SubtitleSidecar row = onlyDownload();

        // The owner has not said "looks right", so the timing check still sets the offset for the pick.
        fitted(taken, row, Verdict.ALIGNED, 750);
        assertThat(row.getOffsetMs()).isEqualTo(750);
        assertThat(row.getOffsetSource()).isEqualTo(SubtitleSidecar.OffsetSource.AUTO);

        // A nudge clears an owner's check but not the pick.
        mvc.perform(put("/api/admin/subtitles/{id}/offset", row.getId()).content("{\"offsetMs\":500}"))
            .andExpect(status().isOk());

        assertThat(row.getOffsetMs()).isEqualTo(500);
        assertThat(row.getOwnerChosenAt()).isEqualTo(NOW);
    }

    @Test
    void aPickNothingElseProvesStillPlaysAndIsNeverDueAgainWhereWithoutTheMarkItWouldFailAndBeReplaced()
        throws Exception {
        searchFindsBothFiles();
        theFileDownloads(5012);
        assertThat(downloadService.due()).extracting(DueDownload::editionId).containsExactly(edition(taken).getId());
        mvc.perform(get(search + "?kind=forced")).andExpect(status().isOk());
        mvc.perform(post(download).content(body(5012, "forced"))).andExpect(status().isOk());
        SubtitleSidecar row = onlyDownload();
        mvc.perform(put("/api/admin/subtitles/{id}/offset", row.getId()).content("{\"offsetMs\":500}"))
            .andExpect(status().isOk());

        // Nothing proves it: its timing does not line up and a comparison with another file says CHECK.
        fitted(taken, row, Verdict.CHECK, 0);
        downloadCheck(taken, row, "CHECK", reference(taken, 5011));

        assertThat(resolve(taken).decision().step()).isEqualTo(Step.PROVEN_DOWNLOAD);
        assertThat(resolve(taken).decision().downloadId()).isEqualTo(row.getId());
        assertThat(resolve(taken).decision().downloadReasons().get(row.getId())).isEqualTo("Plays: you picked it");
        assertThat(downloadService.due()).isEmpty();

        // The same fixture without the marker: the download has failed and the edition is due a replacement.
        row.setOwnerChosenAt(null);
        sidecars.saveAndFlush(row);
        assertThat(resolve(taken).decision().step()).isNotEqualTo(Step.PROVEN_DOWNLOAD);
        assertThat(resolve(taken).decision().hasLiveDownload()).isFalse();
        assertThat(downloadService.due()).extracting(DueDownload::editionId).containsExactly(edition(taken).getId());
    }

    @Test
    void aNewerPickTakesTheMarkFromTheEarlierOneInTheDatabaseAndAPickOfTheOtherKindKeepsItsOwn() throws Exception {
        searchFindsBothFiles();
        OpenSubtitlesResult full = result(6011, "Taken.2008.Full");
        when(api.searchFull(IMDB)).thenReturn(new OpenSubtitlesSearch(1, full, List.of(full), List.of(full)));
        theFileDownloads(5011);
        theFileDownloads(5012);
        StringBuilder fullSrt = new StringBuilder();
        for (int i = 1; i <= SubtitleComparison.FULL_LINES; i++) {
            fullSrt.append(i).append("\n00:00:").append(String.format("%02d", i % 60)).append(",000 --> 00:00:")
                .append(String.format("%02d", i % 60)).append(",500\nLine ").append(i).append("\n\n");
        }
        when(api.download(6011L)).thenReturn(new OpenSubtitlesDownload(
            SrtParser.parse(fullSrt.toString().getBytes(StandardCharsets.UTF_8)), 19, null));

        mvc.perform(get(search + "?kind=forced")).andExpect(status().isOk());
        mvc.perform(post(download).content(body(5011, "forced"))).andExpect(status().isOk());
        mvc.perform(get(search + "?kind=full")).andExpect(status().isOk());
        mvc.perform(post(download).content(body(6011, "full"))).andExpect(status().isOk());
        mvc.perform(get(search + "?kind=forced")).andExpect(status().isOk());
        mvc.perform(post(download).content(body(5012, "forced"))).andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();

        List<SubtitleSidecar> stored = sidecars.findAll();
        SubtitleSidecar first = stored.stream().filter(s -> s.getOpenSubtitlesFileId() == 5011L).findFirst()
            .orElseThrow();
        SubtitleSidecar second = stored.stream().filter(s -> s.getOpenSubtitlesFileId() == 5012L).findFirst()
            .orElseThrow();
        SubtitleSidecar fullRow = stored.stream().filter(s -> s.getOpenSubtitlesFileId() == 6011L).findFirst()
            .orElseThrow();
        assertThat(first.getOwnerChosenAt()).isNull();
        assertThat(second.getOwnerChosenAt()).isEqualTo(NOW);
        assertThat(fullRow.getKind()).isEqualTo(SubtitleKind.FULL);
        assertThat(fullRow.getOwnerChosenAt()).isEqualTo(NOW);
        var reloaded = editionRepository.findWithFilesByFilmIdIn(List.of(taken.getId()));
        var resolution = resolver.resolve(reloaded).get(edition(taken).getId());
        assertThat(resolution.decision().downloadId()).isEqualTo(second.getId());
    }

    private SubtitleSidecar onlyDownload() {
        return sidecars.findAll().stream().filter(SubtitleSidecar::isDownload).findFirst().orElseThrow();
    }

    @Test
    void theAdminListAndTheFilmDetailNeverSayACheckedOrVerifiedForAPick() throws Exception {
        searchFindsBothFiles();
        theFileDownloads(5012);
        mvc.perform(get(search + "?kind=forced")).andExpect(status().isOk());
        mvc.perform(post(download).content(body(5012, "forced"))).andExpect(status().isOk());
        SubtitleSidecar row = sidecars.findAll().stream().filter(SubtitleSidecar::isDownload).findFirst()
            .orElseThrow();

        mvc.perform(get("/api/admin/subtitles"))
            .andExpect(jsonPath("$[?(@.id==" + row.getId() + ")].verified").value(false))
            .andExpect(jsonPath("$[?(@.id==" + row.getId() + ")].served").value(true))
            .andExpect(jsonPath("$[?(@.id==" + row.getId() + ")].servedReason").value("Plays: you picked it"));
        mvc.perform(get("/api/films/{id}", taken.getId()))
            .andExpect(jsonPath("$.editions[0].forcedSubtitle.source").value("DOWNLOAD"))
            .andExpect(jsonPath("$.editions[0].forcedSubtitle.ownerVerified").value(false));
    }

    @Test
    void theSameFilePickedTwiceIsDownloadedOnceAndTheRepeatIs409() throws Exception {
        searchFindsBothFiles();
        theFileDownloads(5011);
        mvc.perform(get(search + "?kind=forced")).andExpect(status().isOk());

        mvc.perform(post(download).content(body(5011, "forced"))).andExpect(status().isOk());
        mvc.perform(post(download).content(body(5011, "forced")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("That file is already this edition's download."));

        verify(api, times(1)).download(5011L);
        assertThat(sidecars.findAll().stream().filter(SubtitleSidecar::isDownload)).hasSize(1);
    }

    @Test
    void aFileTheOwnerRemovedBeforeIsRefusedWithoutSpendingADownload() throws Exception {
        searchFindsBothFiles();
        mvc.perform(get(search + "?kind=forced")).andExpect(status().isOk());
        ForcedDownloadBlock removed = new ForcedDownloadBlock();
        removed.setEditionId(edition(taken).getId());
        removed.setImdbId(IMDB);
        removed.setOsFileId(5011);
        removed.setRemovedAt(NOW);
        blocks.saveAndFlush(removed);

        mvc.perform(post(download).content(body(5011, "forced")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value(
                "You removed that file from this edition earlier, so it is never downloaded for it again."));
        mvc.perform(get(search + "?kind=forced"))
            .andExpect(jsonPath("$.downloadsLeft").value(2));
    }

    @Test
    void anEditionThatHasUsedItsThreeFilesTakesNoMoreOnTheFourth() throws Exception {
        searchFindsBothFiles();
        mvc.perform(get(search + "?kind=forced")).andExpect(status().isOk());
        for (long fileId : new long[] {6001, 6002, 6003}) {
            ForcedDownloadBlock removed = new ForcedDownloadBlock();
            removed.setEditionId(edition(taken).getId());
            removed.setImdbId(IMDB);
            removed.setOsFileId(fileId);
            removed.setRemovedAt(NOW);
            blocks.saveAndFlush(removed);
        }

        mvc.perform(post(download).content(body(5011, "forced")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("This edition has already used its 3 OpenSubtitles files "
                + "(removed ones count), so no more can be downloaded for it."));
    }

    @Test
    void aFileFromTheOtherKindsSearchIs400() throws Exception {
        searchFindsBothFiles();
        mvc.perform(get(search + "?kind=forced")).andExpect(status().isOk());

        mvc.perform(post(download).content(body(5011, "full")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("That file was not in a recent search for this edition. "
                + "Search again."));
    }

    @Test
    void aScanRunningIs409AndNothingIsDownloaded() throws Exception {
        searchFindsBothFiles();
        mvc.perform(get(search + "?kind=forced")).andExpect(status().isOk());
        assertThat(lock.tryAcquire(LibraryLock.SCAN)).isTrue();
        try {
            mvc.perform(post(download).content(body(5011, "forced")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("A scan is running"));
        } finally {
            lock.release();
        }

        assertThat(sidecars.findAll().stream().filter(SubtitleSidecar::isDownload)).isEmpty();
    }

    @Test
    void aFilmWithNoImdbIdIs400() throws Exception {
        taken.setImdbId(null);
        films.saveAndFlush(taken);

        mvc.perform(get(search + "?kind=forced"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(
                "This film has no IMDb id, so OpenSubtitles cannot be searched. Fix its match first."));
    }

    @Test
    void anUnknownEditionIs404() throws Exception {
        mvc.perform(get("/api/admin/editions/999999/opensubtitles?kind=forced"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such edition"));
    }
}
