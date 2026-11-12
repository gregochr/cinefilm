package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.DownloadPlan.Kind;
import gregochr.cinefilm.subtitle.ForcedDownloadService.AddOutcome;
import gregochr.cinefilm.subtitle.SubtitleComparison.Result;
import gregochr.cinefilm.subtitle.SubtitleComparison.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/** The edges of choosing and adding downloads and of checking them, against the real repositories on H2. */
class ForcedDownloadEdgeTest extends ForcedDownloadTestBase {

    private static final Result AGREES = new Result(2, 2, 1.0, 0, Verdict.AGREES, List.of(), 2, false);

    @Autowired
    private ForcedDownloadService service;

    @Autowired
    private SidecarCheckService checkService;

    private Film film() {
        return searchedFilm("Taken", 1, result(5011, "Taken.2008.BluRay"), result(5012, "Taken.2008.WEB"));
    }

    private void ledger(Film film, String imdbId, long fileId) {
        ForcedCheckCandidate row = new ForcedCheckCandidate();
        row.setEditionId(edition(film).getId());
        row.setImdbId(imdbId);
        row.setOsFileId(fileId);
        row.setTriedAt(NOW);
        ledger.saveAndFlush(row);
    }

    private void block(Film film, String imdbId, long fileId) {
        ForcedDownloadBlock block = new ForcedDownloadBlock();
        block.setEditionId(edition(film).getId());
        block.setImdbId(imdbId);
        block.setOsFileId(fileId);
        block.setRemovedAt(NOW);
        blocks.saveAndFlush(block);
    }

    @Test
    void filesComparedOrBlockedUnderAnotherImdbIdDoNotCount() {
        Film film = film();
        ledger(film, "tt0000009", 5011);
        block(film, "tt0000009", 5012);

        DueDownload due = service.due().get(0);

        assertThat(due.candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(5011L, 5012L);
        assertThat(due.remainingFetches()).isEqualTo(3);
    }

    @Test
    void anotherEditionsLedgerBlocksAndDownloadsDoNotCount() {
        Film film = film();
        Film other = searchedFilm("Heat", 1, result(6011, "Heat"), result(6012, "Heat.WEB"));
        ledger(other, IMDB, 6011);
        block(other, IMDB, 6012);
        download(other, 6011);

        DueDownload due = service.due().stream().filter(d -> d.filmId().equals(film.getId())).findFirst()
            .orElseThrow();

        assertThat(due.remainingFetches()).isEqualTo(3);
        assertThat(due.candidates()).hasSize(2);
        assertThat(checkService.due()).extracting(DueCheck::filmId).containsExactly(other.getId());
        assertThat(checkService.due().get(0).triedFileIds()).containsExactly(6011L);
    }

    @Test
    void aFailedFileOnAnEditionWhoseTimelineIsUnknownGetsNoPromotion() {
        Film film = searchedFilm("Towers", 2, result(7001, "Towers"), result(7002, "Towers.WEB"));
        sidecar("Towers - pt1.en.forced.srt", fileId(film, 1), false);
        sidecar("Towers - pt2.en.forced.srt", fileId(film, 2), false);
        edition(film).originalParts().get(0).setDurationMs(null);
        mediaFiles.saveAndFlush(edition(film).originalParts().get(0));

        List<DueDownload> due = service.due();

        // The version of the files cannot be worked out, so nothing is known to have failed: not due.
        assertThat(due).isEmpty();
    }

    @Test
    void anAddIsDroppedWhenTheFilmHasNoCandidatesAnyMoreOrTheFileIsMultiPartNow() {
        Film film = film();
        OpenSubtitlesReference reference = reference(film, 5011);
        DueDownload due = service.due().get(0);

        film.setOsCandidates(OpenSubtitlesResult.listToJson(List.of(
            new OpenSubtitlesResult(1, 5011, "r", 23.976, 1, true, false, null, null, 2))));
        films.saveAndFlush(film);
        assertThat(service.add(due, reference, LATER)).isEqualTo(AddOutcome.DROPPED);

        film.setOsCandidates(null);
        films.saveAndFlush(film);
        assertThat(service.add(due, reference, LATER)).isEqualTo(AddOutcome.DROPPED);
    }

    @Test
    void anAddIsDroppedWhenTheEditionAlreadyHasThreeFilesUsedUp() {
        Film film = film();
        OpenSubtitlesReference reference = reference(film, 5011);
        DueDownload due = service.due().get(0);
        block(film, IMDB, 6001);
        block(film, IMDB, 6002);
        block(film, IMDB, 6003);

        assertThat(service.add(due, reference, LATER)).isEqualTo(AddOutcome.DROPPED);
    }

    @Test
    void anAddIsDroppedWhenTheEditionIsDueForAnotherReasonNowOrTheFilesVersionIsNotTheOneDecidedOn() {
        Film film = film();
        OpenSubtitlesReference candidate = reference(film, 5012);
        DueDownload missing = service.due().get(0);
        SubtitleSidecar owner = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        fileCheck(film, "CHECK", reference(film, 5011));

        // It was due as missing; now the owner's file exists and failed: a replacement, not the same reason.
        assertThat(service.add(missing, candidate, LATER)).isEqualTo(AddOutcome.DROPPED);

        DueDownload replacement = service.due().get(0);
        DueDownload stale = new DueDownload(Kind.REPLACEMENT, replacement.filmId(), replacement.editionId(),
            replacement.imdbId(), replacement.editionName(), null, replacement.candidates(), 3, "an older version");
        assertThat(service.add(stale, candidate, LATER)).isEqualTo(AddOutcome.DROPPED);
        assertThat(owner.getId()).isNotNull();
        assertThat(service.add(replacement, candidate, LATER)).isEqualTo(AddOutcome.ADDED);
    }

    @Test
    void aFileThatFailedAndLooksLikeFullSubtitlesIsLoggedOncePerRunAsNotShown() {
        Film film = film();
        SubtitleSidecar owner = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        owner.setCueCount(300);
        sidecars.saveAndFlush(owner);
        fileCheck(film, "CHECK", reference(film, 5011));
        Logger log = (Logger) LoggerFactory.getLogger(ForcedDownloadService.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        log.addAppender(logged);
        try {
            service.due();
        } finally {
            log.detachAppender(logged);
        }

        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly("Edition "
            + edition(film).getId() + ": the owner's file failed its checks and looks like full subtitles (300 "
            + "lines): nothing is shown until a replacement is proven");
    }

    // --- checking downloads and files ---

    @Test
    void aCheckWhoseReferenceIsNoLongerOneOfTheFilmsCandidatesLeavesTheFileDueAgain() {
        Film film = film();
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        fileCheck(film, "AGREES", reference(film, 5011));
        assertThat(checkService.due()).isEmpty();

        film.setOsCandidates(OpenSubtitlesResult.listToJson(List.of(result(5099, "x"))));
        films.saveAndFlush(film);

        assertThat(checkService.due()).hasSize(1);
    }

    @Test
    void aCheckCountsOnlyWhileTheFilmsBestResultIsAKnownSingleFile() {
        Film film = film();
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        fileCheck(film, "AGREES", reference(film, 5011));
        assertThat(resolve(film).fileCheck()).isNotNull();

        film.setOsBest(new OpenSubtitlesResult(1, 5011, "r", 23.976, 1, true, false, null, null, 2).toJson());
        assertThat(resolve(film).fileCheck()).isNull();

        film.setOsBest(null);
        assertThat(resolve(film).fileCheck()).isNull();
    }

    @Test
    void aLedgerRowOfAnotherImdbIdIsNotTheEditionsLedger() {
        Film film = film();
        download(film, 5011);
        ledger(film, "tt0000009", 5012);

        assertThat(checkService.due().get(0).triedFileIds()).isEmpty();
    }

    @Test
    void filesOnAnEditionWhoseTimelineIsUnknownAreNotDueForACheck() {
        Film film = searchedFilm("Towers", 2, result(7001, "Towers"));
        sidecar("Towers - pt2.en.forced.srt", fileId(film, 2), false);
        edition(film).originalParts().get(0).setDurationMs(null);
        mediaFiles.saveAndFlush(edition(film).originalParts().get(0));

        assertThat(checkService.due()).isEmpty();
    }

    @Test
    void aSavedCheckOfAFileWhoseVersionIsNotTheOneComparedIsDropped() {
        Film film = film();
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        OpenSubtitlesReference reference = reference(film, 5011);
        DueCheck due = checkService.due().get(0);
        DueCheck wrongVersion = new DueCheck(due.filmId(), due.editionId(), due.imdbId(), due.editionName(),
            due.candidates(), due.triedFileIds(), "not the version", false, due.cues());

        assertThat(checkService.save(wrongVersion, reference, AGREES, LATER)).isFalse();
    }
}
