package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.SubtitleComparison.Result;
import gregochr.cinefilm.subtitle.SubtitleComparison.Verdict;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The check of downloads against a second OpenSubtitles file: when a download is due (only when its timing fit could
 * not prove it), that its own file is never its second opinion, that its check is its own row, and that the owner's
 * file keeps its own check while a download plays instead.
 */
class SidecarCheckDownloadsTest extends ForcedDownloadTestBase {

    private static final Result AGREES = new Result(2, 2, 1.0, 0, Verdict.AGREES, List.of(), 2, false);
    private static final Result CHECK = new Result(2, 41, 0.5, 1200, Verdict.CHECK, List.of("Not the same"), 1,
        false);

    @Autowired
    private SidecarCheckService service;

    private Film film() {
        return searchedFilm("Taken", 1, result(5011, "Taken.2008.BluRay"), result(5012, "Taken.2008.WEB"),
            result(5013, "Taken.2008.DVD"));
    }

    private SidecarCheckService enabledTimingService() {
        return new SidecarCheckService(films, sidecars, editionRepository, resolver, checks,
            downloadChecks, ledger, true);
    }

    @Test
    void anUncheckedDownloadIsDueForASecondOpinionWithItsOwnFileLeftOutAndItsOwnCues() {
        Film film = film();
        SubtitleSidecar download = download(film, 5011);
        ForcedCheckCandidate tried = new ForcedCheckCandidate();
        tried.setEditionId(edition(film).getId());
        tried.setImdbId(IMDB);
        tried.setOsFileId(5011);
        tried.setTriedAt(NOW);
        ledger.saveAndFlush(tried);

        List<DueCheck> due = service.due();

        assertThat(due).hasSize(1);
        DueCheck check = due.get(0);
        assertThat(check.isDownload()).isTrue();
        assertThat(check.downloadSidecarId()).isEqualTo(download.getId());
        assertThat(check.candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(5012L, 5013L);
        assertThat(check.triedFileIds()).containsExactly(5011L);
        assertThat(check.servedVersion()).isEqualTo(downloadVersion(film, download));
        assertThat(check.ownerVerified()).isFalse();
        assertThat(check.cues()).containsExactly(new SubtitleCue(10_000, 12_000, "Hello"),
            new SubtitleCue(20_000, 22_500, "World"));
    }

    @Test
    void aDownloadWithNoOtherFileToCompareWithIsNotDue() {
        Film film = searchedFilm("Taken", 1, result(5011, "Taken.2008.BluRay"));
        download(film, 5011);

        assertThat(service.due()).isEmpty();
    }

    @Test
    void aDownloadWaitsForItsTimingFitWhenTheTimingCheckIsOnAndIsComparedAfterAFitThatDidNotProveIt() {
        Film film = film();
        SubtitleSidecar download = download(film, 5011);
        download.setCuesText(SrtParser.format(STARTS.stream().map(s -> new SubtitleCue(s, s + 1_500, "line"))
            .toList()));
        download.setCueCount(STARTS.size());
        sidecars.saveAndFlush(download);
        SidecarCheckService waiting = enabledTimingService();

        assertThat(waiting.due()).isEmpty();

        fitted(film, download, TimingFit.Verdict.CHECK);
        assertThat(waiting.due()).extracting(DueCheck::downloadSidecarId).containsExactly(download.getId());
    }

    @Test
    void aDownloadTimingProvedOrOwnerVerifiedOrAlreadyComparedIsNotDue() {
        Film aligned = film();
        SubtitleSidecar timed = download(aligned, 5011);
        timed.setCuesText(SrtParser.format(STARTS.stream().map(s -> new SubtitleCue(s, s + 1_500, "line"))
            .toList()));
        timed.setCueCount(STARTS.size());
        sidecars.saveAndFlush(timed);
        fitted(aligned, timed, TimingFit.Verdict.ALIGNED);
        assertThat(enabledTimingService().due()).isEmpty();

        Film verified = searchedFilm("Heat", 1, result(6011, "Heat"), result(6012, "Heat.WEB"));
        SubtitleSidecar owned = download(verified, 6011);
        owned.setOwnerVerifiedAt(NOW);
        owned.setVerifiedParts(String.valueOf(fileId(verified, 1)));
        sidecars.saveAndFlush(owned);
        assertThat(service.due()).isEmpty();

        Film compared = searchedFilm("Alien", 1, result(7011, "Alien"), result(7012, "Alien.WEB"));
        SubtitleSidecar done = download(compared, 7011);
        downloadCheck(compared, done, "AGREES", reference(compared, 7012));
        assertThat(service.due()).isEmpty();
    }

    @Test
    void aDownloadThatFailedItsComparisonIsNotComparedAgain() {
        Film film = film();
        SubtitleSidecar download = download(film, 5011);
        downloadCheck(film, download, "CHECK", reference(film, 5012));

        assertThat(service.due()).isEmpty();
    }

    @Test
    void theOwnersFileIsCheckedAlongsideItsDownloadAndTheFileComesFirst() {
        Film film = film();
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        SubtitleSidecar download = download(film, 5011);

        List<DueCheck> due = service.due();

        assertThat(due).extracting(DueCheck::isDownload).containsExactly(false, true);
        assertThat(due.get(1).downloadSidecarId()).isEqualTo(download.getId());
    }

    @Test
    void theOwnersFilesChecksVersionIsTheFilesOwnPickWhileADownloadPlaysInstead() {
        Film film = film();
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        fileCheck(film, "CHECK", reference(film, 5011));
        download(film, 5012);

        // The failed file's check is current, so only the download is due; the file is not checked again.
        assertThat(service.due()).extracting(DueCheck::isDownload).containsExactly(true);
    }

    @Test
    void savingADownloadsCheckWritesItsOwnRowAndNeverTheEditionsRow() {
        Film film = film();
        SubtitleSidecar download = download(film, 5011);
        OpenSubtitlesReference other = reference(film, 5012);
        DueCheck due = service.due().get(0);

        assertThat(service.save(due, other, AGREES, LATER)).isTrue();
        assertThat(service.save(due, other, CHECK, LATER.plusSeconds(60))).isTrue();

        List<ForcedDownloadCheck> rows = downloadChecks.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getSidecarId()).isEqualTo(download.getId());
        assertThat(rows.get(0).getVerdict()).isEqualTo("CHECK");
        assertThat(rows.get(0).getReferenceId()).isEqualTo(other.getId());
        assertThat(rows.get(0).getServedVersion()).isEqualTo(downloadVersion(film, download));
        assertThat(rows.get(0).getCheckedAt()).isEqualTo(LATER.plusSeconds(60));
        assertThat(rows.get(0).getRulesVersion()).isEqualTo(SubtitleCheckBase.RULES_VERSION);
        assertThat(rows.get(0).getBestFileId()).isEqualTo(5011L);
        assertThat(checks.findAll()).isEmpty();
    }

    @Test
    void aDownloadsCheckAgainstItsOwnFileIsNeverSaved() {
        Film film = film();
        SubtitleSidecar download = download(film, 5011);
        OpenSubtitlesReference itself = reference(film, 5011);
        DueCheck due = service.due().get(0);

        assertThat(service.save(due, itself, AGREES, LATER)).isFalse();
        assertThat(downloadChecks.findBySidecarId(download.getId())).isEmpty();
    }

    @Test
    void aDownloadsCheckIsDroppedWhenTheDownloadChangedOrWentMeanwhile() {
        Film film = film();
        SubtitleSidecar download = download(film, 5011);
        OpenSubtitlesReference other = reference(film, 5012);
        DueCheck due = service.due().get(0);

        download.setOffsetMs(250);
        sidecars.saveAndFlush(download);
        assertThat(service.save(due, other, AGREES, LATER)).isFalse();

        sidecars.delete(download);
        sidecars.flush();
        assertThat(service.save(due, other, AGREES, LATER)).isFalse();
        assertThat(downloadChecks.findAll()).isEmpty();
    }

    @Test
    void savingTheOwnersFileCheckStillUsesTheFilesPickNotTheDownloadThatPlaysInstead() {
        Film film = film();
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        download(film, 5012);
        OpenSubtitlesReference reference = reference(film, 5011);
        DueCheck due = service.due().stream().filter(d -> !d.isDownload()).findFirst().orElseThrow();

        assertThat(service.save(due, reference, CHECK, LATER)).isTrue();

        assertThat(checks.findAll()).hasSize(1);
        assertThat(checks.findAll().get(0).getServedVersion()).isEqualTo(fileVersion(film));
        assertThat(downloadChecks.findAll()).isEmpty();
    }

    @Test
    void aDownloadCountsAsASubtitleOfTheFilmButNotAsEvidenceOfAnOwnersFile() {
        Film film = film();
        download(film, 5011);

        assertThat(Set.copyOf(sidecars.findFilmIdsWithPresentSubtitle(SubtitleKind.FORCED)))
            .containsExactly(film.getId());
        assertThat(sidecars.findFilmIdsWithPresentSidecar(SubtitleKind.FORCED)).isEmpty();
        assertThat(service.due()).hasSize(1);
    }
}
