package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.ForcedNeedChoice;
import gregochr.cinefilm.film.FullNeedChoice;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.subtitle.DownloadPlan.Kind;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.FilmNeedDto;
import gregochr.cinefilm.subtitle.ForcedResolver.Resolution;
import gregochr.cinefilm.subtitle.SubtitleDtos.SidecarDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The guard of phase 5b: full English files share the {@code subtitle_sidecar} table (and the download block and
 * check ledger tables) with the forced ones, and no forced path may ever see them. Each test puts full rows beside a
 * film and runs every forced path: what plays, the film detail, the need evidence, the forced WebVTT route, the check
 * run's due list, the timing run's due list, the download due list and its caps, and Admin's forced list and
 * conflicts, against the real services on H2.
 */
class FullSidecarIsolationTest extends ForcedDownloadTestBase {

    @Autowired
    private ForcedNeedService need;

    @Autowired
    private SidecarCheckService checkRun;

    @Autowired
    private ForcedDownloadService downloadRun;

    @Autowired
    private ForcedCheckCandidateRepository candidates;

    /** Everything the forced paths say about one film, so two states can be compared for equality. */
    private record Seen(List<Long> playingIds, ForcedChoice.Source source, boolean fileConflict, List<Long> downloadIds,
                        String vtt, FilmNeedDto needRow, List<DueCheck> checkDue, List<Long> timingDue,
                        List<DueDownload> downloadDue, List<SidecarDto> adminForcedRows) {
    }

    private Seen seen(Film film) {
        Resolution resolution = resolve(film);
        String vtt;
        try {
            vtt = forced.vtt(edition(film).getId());
        } catch (NotFoundException e) {
            vtt = "404 " + e.getMessage();
        }
        return new Seen(
            resolution.chosen().slots().stream().map(s -> s.sidecar().getId()).toList(),
            resolution.decision().source(), resolution.fileConflict(),
            resolution.downloads().stream().map(d -> d.sidecar().getId()).toList(), vtt,
            need.list().films().stream().filter(f -> f.filmId().equals(film.getId())).findFirst().orElseThrow(),
            checkRun.due(), timing.dueFilmIds(), downloadRun.due(),
            admin.list().stream().filter(d -> "FORCED".equals(d.kind())).toList());
    }

    /** A FULL download of the film's edition for the OpenSubtitles file id (the only way a full one exists yet). */
    private SubtitleSidecar fullDownload(Film film, long osFileId) {
        SubtitleSidecar row = download(film, osFileId);
        row.setKind(SubtitleKind.FULL);
        return sidecars.saveAndFlush(row);
    }

    /** Gives the film's files a size and time, so that the timing run would find a served subtitle due. */
    private void withFingerprints(Film film) {
        for (MediaFile file : edition(film).originalParts()) {
            file.setSizeBytes(1_000L + file.getId());
            file.setModifiedAt(NOW);
            mediaFiles.saveAndFlush(file);
        }
    }

    private Film neededFilmWithForcedFile(String title) {
        Film film = searchedFilm(title, 1, result(5011, title + ".BluRay"), result(5012, title + ".WEB"),
            result(5013, title + ".DVD"));
        film.setForcedNeedOwner(ForcedNeedChoice.NEEDED);
        films.saveAndFlush(film);
        withFingerprints(film);
        sidecar(title + ".en.forced.srt", fileId(film, 1), false);
        return film;
    }

    @Test
    void fullRowsBesideAForcedFileChangeNothingAnyForcedPathSays() throws Exception {
        Film taken = neededFilmWithForcedFile("Taken");
        // A second needed film with nothing that plays is due a download, so the due list and its cap are in play.
        Film heat = searchedFilm("Heat", 1, result(6011, "Heat.BluRay"), result(6012, "Heat.WEB"),
            result(6013, "Heat.DVD"));
        heat.setForcedNeedOwner(ForcedNeedChoice.NEEDED);
        films.saveAndFlush(heat);
        Seen before = seen(taken);
        assertThat(before.playingIds()).hasSize(1);
        assertThat(before.checkDue()).isNotEmpty();
        assertThat(before.timingDue()).containsExactly(taken.getId());
        assertThat(before.downloadDue()).singleElement().satisfies(due -> {
            assertThat(due.filmId()).isEqualTo(heat.getId());
            assertThat(due.remainingFetches()).isEqualTo(3);
        });
        assertThat(before.vtt()).startsWith("WEBVTT");

        // The full rows sit on the very same part file, as a file and as a download whose OpenSubtitles file is one
        // of the film's forced candidates: if any path mixed the kinds it would see a conflict, a second download
        // or a used-up cap.
        SubtitleSidecar fullFile = fullSidecar("Taken.en.srt", fileId(taken, 1), false);
        SubtitleSidecar fullDownload = fullDownload(taken, 5012);
        fullSidecar("Heat.en.srt", fileId(heat, 1), false);
        fullDownload(heat, 6012);
        assertThat(sidecars.findByMediaFileIdInAndKind(List.of(fileId(taken, 1)), SubtitleKind.FULL))
            .extracting(SubtitleSidecar::getId).containsExactlyInAnyOrder(fullFile.getId(), fullDownload.getId());

        Seen after = seen(taken);

        assertThat(after).isEqualTo(before);
        assertThat(after.downloadDue().get(0).remainingFetches()).isEqualTo(3);
        assertThat(after.downloadDue().get(0).candidates()).extracting(c -> c.fileId())
            .containsExactly(6011L, 6012L, 6013L);
        assertThat(after.needRow().reason()).isEqualTo("OWNER");
        assertThat(after.adminForcedRows()).hasSize(1).allSatisfy(row -> {
            assertThat(row.state()).isEqualTo("matched");
            assertThat(row.served()).isTrue();
        });
    }

    @Test
    void theFilmDetailKeepsTheForcedSubtitleAndOffersTheFullOneSeparately() throws Exception {
        Film taken = neededFilmWithForcedFile("Taken");
        fullSidecar("Taken.en.srt", fileId(taken, 1), false);

        mvc.perform(get("/api/films/{id}", taken.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.editions[0].forcedSubtitle.source").value("SIDECAR"))
            .andExpect(jsonPath("$.editions[0].fullSubtitle.source").value("SIDECAR"));
        mvc.perform(get("/api/editions/{id}/forced-subtitles.vtt", edition(taken).getId()))
            .andExpect(status().isOk())
            .andExpect(content().string(forced.vtt(edition(taken).getId())));
        assertThat(forced.forEditions(taken.getEditions())).containsOnlyKeys(edition(taken).getId());
        assertThat(forced.forEditions(taken.getEditions()).get(edition(taken).getId()).source()).isEqualTo("SIDECAR");
        assertThat(forced.playableEditionIds(taken.getEditions())).containsExactly(edition(taken).getId());
    }

    @Test
    void aFullFileIsNotAForcedConflictNorEvidenceNorAForcedDownloadSlot() {
        Film heat = searchedFilm("Heat", 1, result(5011, "Heat.BluRay"), result(5012, "Heat.WEB"));
        heat.setForcedNeedOwner(ForcedNeedChoice.NEEDED);
        films.saveAndFlush(heat);
        sidecar("Heat.en.forced.srt", fileId(heat, 1), false);
        sidecar("Heat.eng.forced.srt", fileId(heat, 1), false);
        fullSidecar("Heat.en.srt", fileId(heat, 1), false);

        // Two forced files for one part are a conflict, and the full file is not a third party to it.
        assertThat(resolve(heat).fileConflict()).isTrue();
        assertThat(admin.list()).filteredOn(d -> "FORCED".equals(d.kind())).extracting(SidecarDto::state)
            .containsOnly("conflict");
        assertThat(admin.list()).filteredOn(d -> "FULL".equals(d.kind())).extracting(SidecarDto::state)
            .containsExactly("matched");
    }

    @Test
    void aFilmWhoseOnlySidecarIsFullHasNoForcedSubtitleAndNoForcedEvidence() throws Exception {
        Film taken = filmWithParts("Taken", 1, 90);
        fullSidecar("Taken.en.srt", fileId(taken, 1), false);
        fullDownload(taken, 5012);
        withFingerprints(taken);

        Seen seen = seen(taken);

        assertThat(seen.playingIds()).isEmpty();
        assertThat(seen.source()).isEqualTo(ForcedChoice.Source.NONE);
        assertThat(seen.downloadIds()).isEmpty();
        assertThat(seen.vtt()).isEqualTo("404 No forced subtitles");
        assertThat(seen.needRow().need()).isNotEqualTo("NEEDED");
        assertThat(seen.needRow().reason()).isNotEqualTo("SIDECAR");
        assertThat(seen.needRow().hasForcedSubtitle()).isFalse();
        assertThat(seen.checkDue()).isEmpty();
        assertThat(seen.timingDue()).isEmpty();
        assertThat(seen.downloadDue()).isEmpty();
        assertThat(need.isNeeded(taken.getId())).isFalse();
        assertThat(forced.forEditions(taken.getEditions())).isEmpty();
        assertThat(forced.playableEditionIds(taken.getEditions())).isEmpty();
        assertThat(forced.served(taken.getEditions())).isEmpty();
        assertThatThrownBy(() -> forced.vtt(edition(taken).getId())).isInstanceOf(NotFoundException.class);
        mvc.perform(get("/api/films/{id}", taken.getId()))
            .andExpect(jsonPath("$.editions[0].forcedSubtitle").value((Object) null))
            .andExpect(jsonPath("$.editions[0].fullSubtitle.source").value("SIDECAR"));
        assertThat(seen.adminForcedRows()).isEmpty();
        assertThat(admin.list()).extracting(SidecarDto::kind).containsOnly("FULL");
    }

    @Test
    void aSearchedFilmWhoseOnlySidecarIsFullIsNeverCheckedOrTimedAndItsNeedIsNotFromTheSidecar() {
        Film taken = searchedFilm("Taken", 1, result(5011, "Taken.BluRay"), result(5012, "Taken.WEB"));
        fullSidecar("Taken.en.srt", fileId(taken, 1), false);
        fullDownload(taken, 5012);
        withFingerprints(taken);

        Seen seen = seen(taken);

        assertThat(seen.needRow().reason()).isEqualTo("OPENSUBTITLES");
        assertThat(seen.checkDue()).isEmpty();
        assertThat(seen.timingDue()).isEmpty();
        assertThat(seen.downloadDue()).singleElement().satisfies(due -> {
            assertThat(due.kind()).isEqualTo(Kind.MISSING);
            assertThat(due.remainingFetches()).isEqualTo(3);
        });
    }

    @Test
    void aNeededFilmWhoseOnlySidecarIsFullIsStillDueAMissingForcedDownloadWithTheWholeCap() {
        Film film = searchedFilm("Taken", 1, result(5011, "Taken.BluRay"), result(5012, "Taken.WEB"),
            result(5013, "Taken.DVD"), result(5014, "Taken.Extended"));
        film.setForcedNeedOwner(ForcedNeedChoice.NEEDED);
        films.saveAndFlush(film);
        fullSidecar("Taken.en.srt", fileId(film, 1), false);
        fullDownload(film, 5011);
        fullDownload(film, 5012);

        List<DueDownload> due = downloadRun.due();

        assertThat(due).hasSize(1);
        assertThat(due.get(0).kind()).isEqualTo(Kind.MISSING);
        assertThat(due.get(0).remainingFetches()).isEqualTo(3);
        assertThat(due.get(0).candidates()).extracting(c -> c.fileId()).containsExactly(5011L, 5012L, 5013L, 5014L);
    }

    @Test
    void theBlockAndLedgerOfOneKindDoNotHideFilesFromTheOther() {
        Film film = searchedFilm("Taken", 1, result(5011, "Taken.BluRay"), result(5012, "Taken.WEB"));
        film.setForcedNeedOwner(ForcedNeedChoice.NEEDED);
        films.saveAndFlush(film);
        ForcedDownloadBlock fullBlock = new ForcedDownloadBlock();
        fullBlock.setKind(SubtitleKind.FULL);
        fullBlock.setEditionId(edition(film).getId());
        fullBlock.setImdbId(IMDB);
        fullBlock.setOsFileId(5011);
        fullBlock.setRemovedAt(NOW);
        blocks.saveAndFlush(fullBlock);
        ForcedCheckCandidate fullTried = new ForcedCheckCandidate();
        fullTried.setKind(SubtitleKind.FULL);
        fullTried.setEditionId(edition(film).getId());
        fullTried.setImdbId(IMDB);
        fullTried.setOsFileId(5012);
        fullTried.setTriedAt(NOW);
        candidates.saveAndFlush(fullTried);

        assertThat(blocks.findByEditionIdInAndKind(List.of(edition(film).getId()), SubtitleKind.FORCED)).isEmpty();
        assertThat(candidates.findByEditionIdInAndKind(List.of(edition(film).getId()), SubtitleKind.FORCED))
            .isEmpty();
        assertThat(candidates.existsByEditionIdAndImdbIdAndOsFileIdAndKind(edition(film).getId(), IMDB, 5012,
            SubtitleKind.FORCED)).isFalse();
        assertThat(downloadRun.due()).singleElement().satisfies(due -> {
            assertThat(due.remainingFetches()).isEqualTo(3);
            assertThat(due.candidates()).extracting(c -> c.fileId()).containsExactly(5011L, 5012L);
        });
    }

    @Test
    void fullFilesAreListedWithAKindAndOnlyThoseAreOfferedAsFull() {
        Film taken = neededFilmWithForcedFile("Taken");
        SubtitleSidecar full = fullSidecar("Taken.en.srt", fileId(taken, 1), false);

        List<SidecarDto> listed = admin.list();

        assertThat(listed).extracting(SidecarDto::path).containsExactly("Taken.en.forced.srt", "Taken.en.srt");
        SidecarDto fullRow = listed.stream().filter(d -> d.id().equals(full.getId())).findFirst().orElseThrow();
        assertThat(fullRow.kind()).isEqualTo("FULL");
        assertThat(fullRow.served()).isTrue();
        assertThat(fullRow.check()).isNull();
        assertThat(fullRow.alignment()).isNull();
        assertThat(fullRow.replacedByDownload()).isFalse();
    }

    /** Gives a searched film the full-subtitle need and a full search that found three candidates. */
    private Film withFullSearch(Film film) {
        film.setFullNeedOwner(FullNeedChoice.NEEDED);
        film.setOsFullSearchedImdbId(IMDB);
        film.setOsFullSearchedAt(NOW);
        film.setOsFullCandidates(OpenSubtitlesResult.listToJson(List.of(result(8011, "Full.BluRay"),
            result(8012, "Full.WEB"), result(8013, "Full.DVD"))));
        return films.saveAndFlush(film);
    }

    private List<DueDownload> dueOf(Kind kind) {
        return downloadRun.due().stream().filter(d -> d.kind() == kind).toList();
    }

    @Test
    void forcedFilesDownloadsAndBlocksNeverSatisfyOrUseUpTheFullDue() {
        Film film = withFullSearch(neededFilmWithForcedFile("Taken"));
        List<DueDownload> before = dueOf(Kind.FULL);
        assertThat(before).singleElement().satisfies(due -> {
            assertThat(due.remainingFetches()).isEqualTo(3);
            assertThat(due.candidates()).extracting(c -> c.fileId()).containsExactly(8011L, 8012L, 8013L);
        });

        // A forced download and forced blocks and ledger rows on the same edition, with the very same file ids.
        download(film, 8011);
        for (long fileId : new long[] {8011, 8012, 8013}) {
            ForcedDownloadBlock block = new ForcedDownloadBlock();
            block.setKind(SubtitleKind.FORCED);
            block.setEditionId(edition(film).getId());
            block.setImdbId(IMDB);
            block.setOsFileId(fileId);
            block.setRemovedAt(NOW);
            blocks.saveAndFlush(block);
            ForcedCheckCandidate tried = new ForcedCheckCandidate();
            tried.setKind(SubtitleKind.FORCED);
            tried.setEditionId(edition(film).getId());
            tried.setImdbId(IMDB);
            tried.setOsFileId(fileId);
            tried.setTriedAt(NOW);
            candidates.saveAndFlush(tried);
        }

        assertThat(dueOf(Kind.FULL)).isEqualTo(before);
    }

    @Test
    void aFullDownloadSatisfiesTheFullDueButNeverTheForcedOneOrItsCap() {
        Film film = withFullSearch(searchedFilm("Taken", 1, result(5011, "Taken.BluRay"),
            result(5012, "Taken.WEB"), result(5013, "Taken.DVD")));
        film.setForcedNeedOwner(ForcedNeedChoice.NEEDED);
        films.saveAndFlush(film);
        assertThat(downloadRun.due()).extracting(DueDownload::kind).containsExactly(Kind.FULL, Kind.MISSING);

        fullDownload(film, 8011);

        List<DueDownload> due = downloadRun.due();
        assertThat(due).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo(Kind.MISSING);
            assertThat(item.remainingFetches()).isEqualTo(3);
            assertThat(item.candidates()).extracting(c -> c.fileId()).containsExactly(5011L, 5012L, 5013L);
        });
    }

    @Test
    void aFullDownloadIsNeverCheckedOrTimedByTheForcedRuns() {
        Film film = withFullSearch(searchedFilm("Taken", 1, result(5011, "Taken.BluRay"),
            result(5012, "Taken.WEB")));
        fullDownload(film, 8011);
        withFingerprints(film);

        assertThat(checkRun.due()).isEmpty();
        assertThat(timing.dueFilmIds()).isEmpty();
        assertThat(resolve(film).downloads()).isEmpty();
    }
}
